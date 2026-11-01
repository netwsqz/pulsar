package com.example.pulsar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载调度器的行为：串行、只补缺口、失败重试后跳过、取消保留已完成、进度聚合、失败静默。
 *
 * 时间推进一律用**有界**的 [advanceTimeBy] + [runCurrent]，绝不用 `advanceUntilIdle`：
 * 调度器里有 delay 退避 + 可能挂住的下载，虚拟时间活锁会卡死 gradle daemon（仓库踩过）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSchedulerTest {

    private val albumA = DownloadTarget(
        serverUrl = "https://server.test",
        userId = "user-1",
        kind = DownloadKind.ALBUM,
        id = "album-a",
        name = "专辑 A",
        tracks = listOf(track("t1"), track("t2")),
    )

    private val albumB = DownloadTarget(
        serverUrl = "https://server.test",
        userId = "user-1",
        kind = DownloadKind.ALBUM,
        id = "album-b",
        name = "专辑 B",
        tracks = listOf(track("b1")),
    )

    @Test
    fun `串行：第二个目标等第一个下完才开始`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.gates += "stream/t1" to CompletableDeferred()
        val scheduler = scheduler(downloads)

        scheduler.enqueue(albumA)
        scheduler.enqueue(albumB)
        runCurrent()

        // A 卡在第一首，B 必须还在排队
        assertEquals(listOf("stream/t1"), downloads.downloadCalls)
        assertEquals(DownloadState.Downloading(0, 0), scheduler.stateOf(albumB))

        downloads.gates.getValue("stream/t1").complete(Unit)
        runCurrent()

        assertEquals(listOf("stream/t1", "stream/t2", "stream/b1"), downloads.downloadCalls)
        assertEquals(DownloadState.Downloaded, scheduler.stateOf(albumA))
        assertEquals(DownloadState.Downloaded, scheduler.stateOf(albumB))
    }

    @Test
    fun `只下缺的：已有完整副本的曲目跳过`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.completeKeys += "stream/t1"
        val scheduler = scheduler(downloads)

        scheduler.enqueue(albumA)
        runCurrent()

        assertEquals(listOf("stream/t2"), downloads.downloadCalls)
        assertEquals(DownloadState.Downloaded, scheduler.stateOf(albumA))
    }

    @Test
    fun `失败重试三次后跳过这一首，继续下一首`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.failingKeys += "stream/t1"
        val scheduler = scheduler(downloads)

        scheduler.enqueue(albumA)
        runCurrent()
        assertEquals(1, downloads.downloadCalls.count { it == "stream/t1" })

        advanceTimeBy(1_000); runCurrent() // 第 2 次尝试
        advanceTimeBy(2_000); runCurrent() // 第 3 次尝试
        advanceTimeBy(4_000); runCurrent() // 第 4 次尝试仍失败 → 放弃这首
        runCurrent()                       // t2 照常下

        assertEquals(4, downloads.downloadCalls.count { it == "stream/t1" })
        assertEquals(
            listOf("stream/t1", "stream/t1", "stream/t1", "stream/t1", "stream/t2"),
            downloads.downloadCalls
        )
        // 失败不抛异常，只体现在状态里
        assertEquals(DownloadState.PartiallyFailed(failed = 1), scheduler.stateOf(albumA))
    }

    @Test
    fun `取消：已下完的保留，重下只补缺口`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.gates += "stream/t2" to CompletableDeferred()
        val ledger = FakeLedgerStore()
        val scheduler = scheduler(downloads, ledger)

        scheduler.enqueue(albumA)
        runCurrent() // t1 下完，t2 卡住

        scheduler.cancel(albumA)
        runCurrent()

        assertEquals(DownloadState.Canceled, scheduler.stateOf(albumA))
        assertTrue("已下完的不能被回滚", downloads.completeKeys.contains("stream/t1"))
        assertFalse(downloads.completeKeys.contains("stream/t2"))
        // 名单留着（中途取消 = 打算下的那批还在）
        assertEquals(listOf("t1", "t2"), ledger.entries.single().trackIds)

        // 再点一次：只补缺的
        downloads.gates.clear()
        scheduler.enqueue(albumA)
        runCurrent()

        assertEquals(listOf("stream/t1", "stream/t2", "stream/t2"), downloads.downloadCalls)
        assertEquals(DownloadState.Downloaded, scheduler.stateOf(albumA))
    }

    @Test
    fun `进度聚合：已下字节和总字节按已完成曲目累加`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.progress += "stream/t1" to (40L to 100L)
        downloads.progress += "stream/t2" to (60L to 200L)
        val gate = CompletableDeferred<Unit>()
        downloads.afterProgressGates += "stream/t2" to gate
        val scheduler = scheduler(downloads)

        scheduler.enqueue(albumA)
        runCurrent()

        // t1 已完成（40/100），t2 报完进度（60/200）被卡住 → 整批 100/300
        assertEquals(DownloadState.Downloading(downloadedBytes = 100, totalBytes = 300), scheduler.stateOf(albumA))

        gate.complete(Unit)
        runCurrent()
        assertEquals(DownloadState.Downloaded, scheduler.stateOf(albumA))
    }

    @Test
    fun `入队时立刻把全部曲目写进名单`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.gates += "stream/t1" to CompletableDeferred()
        val ledger = FakeLedgerStore()
        val scheduler = scheduler(downloads, ledger)

        scheduler.enqueue(albumA)
        runCurrent()

        val entry = ledger.entries.single()
        assertEquals("https://server.test", entry.serverUrl)
        assertEquals("user-1", entry.userId)
        assertEquals(DownloadKind.ALBUM, entry.kind)
        assertEquals("album-a", entry.id)
        assertEquals("专辑 A", entry.name)
        assertEquals("当时全部曲目，包括还没下的那首", listOf("t1", "t2"), entry.trackIds)
        scheduler.cancel(albumA)
    }

    @Test
    fun `全部曲目都已完整时直接给已下载，不发生任何下载`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.completeKeys += listOf("stream/t1", "stream/t2")
        val scheduler = scheduler(downloads)

        scheduler.enqueue(albumA)
        runCurrent()

        assertTrue(downloads.downloadCalls.isEmpty())
        assertEquals(DownloadState.Downloaded, scheduler.stateOf(albumA))
    }

    @Test
    fun `没有入队目标时不做任何事`() = runTest {
        val downloads = FakeMediaDownloads()
        val scheduler = scheduler(downloads)

        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(downloads.downloadCalls.isEmpty())
        assertTrue(scheduler.state.value.isEmpty())
    }

    @Test
    fun `未入队的目标显示未下载`() = runTest {
        val scheduler = scheduler(FakeMediaDownloads())
        assertEquals(DownloadState.NotDownloaded, scheduler.stateOf(albumA))
    }

    private fun DownloadScheduler.stateOf(target: DownloadTarget): DownloadState =
        state.value[target.targetId] ?: DownloadState.NotDownloaded

    private fun TestScope.scheduler(
        downloads: FakeMediaDownloads = FakeMediaDownloads(),
        ledger: FakeLedgerStore = FakeLedgerStore(),
    ) = DownloadScheduler(scope = backgroundScope, downloads = downloads, ledger = ledger)

    private fun track(id: String) = DownloadTrack(
        id = id,
        url = "https://server.test/Videos/$id/stream?static=true",
        key = "stream/$id",
    )

    /** 可通过闸门/失败配置控制每次下载行为的 Fake；下载记录按调用次数累加。 */
    private class FakeMediaDownloads : MediaDownloads {
        val completeKeys = mutableSetOf<String>()
        val failingKeys = mutableSetOf<String>()
        /** key → 下载开始前卡住的闸门。 */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        /** key → 报完进度后卡住的闸门。 */
        val afterProgressGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        /** key → 下载进度（默认 1/1）。 */
        val progress = mutableMapOf<String, Pair<Long, Long>>()
        val downloadCalls = mutableListOf<String>()

        override suspend fun hasCompleteCopy(key: String): Boolean = key in completeKeys

        override suspend fun usedBytes(): Long = 0L

        override suspend fun completeCount(): Int = completeKeys.size

        override suspend fun completeKeys(): Set<String> = completeKeys.toSet()

        override suspend fun download(
            url: String,
            key: String,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): Boolean {
            downloadCalls += key
            gates[key]?.await()
            if (key in failingKeys) return false
            val (bytes, total) = progress[key] ?: (1L to 1L)
            onProgress(bytes, total)
            afterProgressGates[key]?.await()
            completeKeys += key
            return true
        }

        override suspend fun remove(key: String) {
            completeKeys -= key
        }
    }

    private class FakeLedgerStore : DownloadLedgerStore {
        var entries = mutableListOf<DownloadEntry>()
        var replaceCalls = 0

        override suspend fun load() = DownloadLedger(entries.toList())

        override suspend fun replaceAll(entries: List<DownloadEntry>) {
            replaceCalls++
            this.entries = entries.toMutableList()
        }

        override suspend fun entriesFor(serverUrl: String, userId: String): List<DownloadEntry> =
            entries.filter { it.serverUrl == serverUrl && it.userId == userId }


    }
}
