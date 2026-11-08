package com.example.pulsar.download

import com.example.pulsar.DownloadEntry
import com.example.pulsar.DownloadKind
import com.example.pulsar.DownloadLedger
import com.example.pulsar.DownloadLedgerStore
import com.example.pulsar.DownloadScheduler
import com.example.pulsar.MediaDownloads
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 「下载唯一真相」的测试种子（`docs/download-library-refactor.md` Step 1）。
 *
 * 核心口径：可见曲目 = **字节完整 ∩ 当前账号名单**。字节是缓存键（播放 URL），
 * 名单里是曲目 id，所以中间必须过一次「曲目 → 键」映射；本测试用 `stream/{id}` 模拟。
 *
 * 兜底口径（与改前 ViewModel 一致，不许简化掉）：
 * - 名单未注入 / 读失败 → 退化成只看字节真相（不按曲目 id 过滤）；
 * - 字节层读失败 → 空集合，不抛给界面；
 * - `CancellationException` 一律重新抛出。
 */
class DownloadLibraryTest {

    private val user1 = AccountScope("https://server.test", "user-1")
    private val user2 = AccountScope("https://server.test", "user-2")

    private fun track(id: String) = Track(
        id = id,
        title = "曲目 $id",
        artist = "歌手",
        album = "专辑",
        durationMs = 1_000L,
        imageTag = null
    )

    /** 键解析器：生产是 streamUrl，测试里与 Fake 的 completeKeys 对齐。 */
    private fun keyOf(track: Track): String? = "stream/${track.id}"

    private fun entry(
        trackIds: List<String>,
        userId: String = "user-1",
        serverUrl: String = "https://server.test",
        kind: DownloadKind = DownloadKind.ALBUM,
        id: String = "album-1"
    ) = DownloadEntry(
        serverUrl = serverUrl,
        userId = userId,
        kind = kind,
        id = id,
        name = "专辑",
        trackIds = trackIds,
        createdAt = 1L
    )

    // ── 可见性：字节 ∩ 名单 ──────────────────────────────────────────

    @Test
    fun `字节完整但不在当前账号名单 → 不可见`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/a", "stream/b")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a"), track("b")), ::keyOf)

        assertEquals(setOf("a"), snapshot.visibleTrackIds)
        // 字节集合是共享的：b 不可见，但它的字节仍在 completeKeys 里
        assertEquals(setOf("stream/a", "stream/b"), snapshot.completeKeys)
    }

    @Test
    fun `名单里有但字节不完整 → 不可见`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a", "b")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a"), track("b")), ::keyOf)

        assertEquals(setOf("a"), snapshot.visibleTrackIds)
    }

    @Test
    fun `字节完整、名单也包含，但算不出缓存键 → 不可见`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a"))) { null }

        assertTrue(snapshot.visibleTrackIds.isEmpty())
    }

    @Test
    fun `未登录（账号为空）→ 可见集合为空，但字节与统计照旧`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
            usedBytesValue = 123L
            completeCountValue = 1
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)

        val snapshot = library.refresh(listOf(track("a")), ::keyOf)

        assertTrue("退出登录不能露出任何可见曲目", snapshot.visibleTrackIds.isEmpty())
        assertEquals(setOf("stream/a"), snapshot.completeKeys)
        assertEquals(DownloadStats(usedBytes = 123L, completeCount = 1), snapshot.stats)
    }

    // ── 兜底口径（必须与改前一致）─────────────────────────────────────

    @Test
    fun `名单未注入 → 退化成只看字节真相`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
        }
        val library = library(downloads, ledger = null)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a"), track("b")), ::keyOf)

        // 没有名单就无从判断归属：字节完整的都算（只多不少，不误筛）
        assertEquals(setOf("a"), snapshot.visibleTrackIds)
    }

    @Test
    fun `名单读失败 → 退化成只看字节真相，不抛异常`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("b")))
            failRead = true
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a"), track("b")), ::keyOf)

        assertEquals(setOf("a"), snapshot.visibleTrackIds)
    }

    @Test
    fun `字节层读失败 → 空集合，不抛异常`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            failCompleteKeys = RuntimeException("cache broken")
            failStats = RuntimeException("cache broken")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a")), ::keyOf)

        assertTrue(snapshot.completeKeys.isEmpty())
        assertTrue(snapshot.visibleTrackIds.isEmpty())
        assertEquals(DownloadStats(usedBytes = 0L, completeCount = 0), snapshot.stats)
    }

    @Test
    fun `统计读失败只降级统计，不影响可见集合`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
            failStats = RuntimeException("stats broken")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a")), ::keyOf)

        assertEquals(setOf("a"), snapshot.visibleTrackIds)
        assertEquals(DownloadStats(usedBytes = 0L, completeCount = 0), snapshot.stats)
    }

    @Test
    fun `CancellationException 一律重新抛出`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            failCompleteKeys = CancellationException("cancel")
        }
        val library = library(downloads, FakeDownloadLedgerStore())
        library.setAccount(user1)

        try {
            library.refresh(listOf(track("a")), ::keyOf)
            fail("CancellationException 必须重新抛出，不能吞成空集合")
        } catch (expected: CancellationException) {
            // 预期
        }
    }

    @Test
    fun `名单读取抛 CancellationException 也重新抛出`() = runTest {
        val downloads = FakeMediaDownloads().apply { completeKeys += "stream/a" }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
            failReadWithCancellation = true
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        try {
            library.refresh(listOf(track("a")), ::keyOf)
            fail("CancellationException 必须重新抛出，不能当成名单不可用")
        } catch (expected: CancellationException) {
            // 预期
        }
    }

    // ── 账号作用域 ──────────────────────────────────────────────────

    @Test
    fun `换账号后可见集合跟着换，字节集合不变`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/a", "stream/b")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                entry(trackIds = listOf("a"), userId = "user-1"),
                entry(trackIds = listOf("b"), userId = "user-2")
            )
        }
        val library = library(downloads, ledger)
        val tracks = listOf(track("a"), track("b"))

        library.setAccount(user1)
        val first = library.refresh(tracks, ::keyOf)
        assertEquals(setOf("a"), first.visibleTrackIds)

        library.setAccount(user2)
        val second = library.refresh(tracks, ::keyOf)
        assertEquals(setOf("b"), second.visibleTrackIds)
        // ADR-0007：音频字节共享，换账号不改变 completeKeys
        assertEquals(first.completeKeys, second.completeKeys)
        assertEquals(setOf("stream/a", "stream/b"), second.completeKeys)
    }

    @Test
    fun `退出登录 setAccount null → 可见清空，字节与统计不丢`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
            usedBytesValue = 9_999L
            completeCountValue = 1
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)
        val before = library.refresh(listOf(track("a")), ::keyOf)
        assertEquals(setOf("a"), before.visibleTrackIds)

        library.setAccount(null)

        // ADR-0007：退出登录不清下载。字节和统计保留，只是没人登录了所以不可见。
        val after = library.state.value
        assertTrue(after.visibleTrackIds.isEmpty())
        assertEquals(before.completeKeys, after.completeKeys)
        assertEquals(before.stats, after.stats)
    }

    // ── 统计与共享 ──────────────────────────────────────────────────

    @Test
    fun `stats 账号无关`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/a", "stream/b")
            usedBytesValue = 12_345L
            completeCountValue = 2
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                entry(trackIds = listOf("a"), userId = "user-1"),
                entry(trackIds = listOf("b"), userId = "user-2")
            )
        }
        val library = library(downloads, ledger)
        val tracks = listOf(track("a"), track("b"))

        library.setAccount(user1)
        val mine = library.refresh(tracks, ::keyOf)
        library.setAccount(user2)
        val other = library.refresh(tracks, ::keyOf)

        assertEquals(DownloadStats(usedBytes = 12_345L, completeCount = 2), mine.stats)
        assertEquals(mine.stats, other.stats)
    }

    @Test
    fun `completeKeys 是账号无关的共享集合`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/a", "stream/b", "stream/c")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                entry(trackIds = listOf("a"), userId = "user-1"),
                entry(trackIds = listOf("b"), userId = "user-2")
            )
        }
        val library = library(downloads, ledger)

        library.setAccount(user1)
        val mine = library.refresh(listOf(track("a")), ::keyOf)
        library.setAccount(user2)
        val other = library.refresh(listOf(track("b")), ::keyOf)

        // c 两个账号都没「拥有」，但字节在盘上，所以两个快照都看得到
        assertEquals(setOf("stream/a", "stream/b", "stream/c"), mine.completeKeys)
        assertEquals(mine.completeKeys, other.completeKeys)
    }

    @Test
    fun `refresh 返回的快照与 state 是同一份`() = runTest {
        val downloads = FakeMediaDownloads().apply { completeKeys += "stream/a" }
        val library = library(downloads, FakeDownloadLedgerStore())
        library.setAccount(user1)

        val snapshot = library.refresh(listOf(track("a")), ::keyOf)

        assertSame(snapshot, library.state.value)
    }

    // ── 测试辅助 ────────────────────────────────────────────────────

    private fun TestScope.library(
        downloads: FakeMediaDownloads,
        ledger: DownloadLedgerStore?
    ): DownloadLibrary {
        val scheduler = DownloadScheduler(
            scope = backgroundScope,
            downloads = downloads,
            ledger = ledger ?: FakeDownloadLedgerStore()
        )
        return DownloadLibrary(
            downloads = downloads,
            ledger = ledger,
            scheduler = scheduler,
            scope = backgroundScope
        )
    }

    /** 可通过字段控制「字节真相」与故障注入的 Fake；不参与下载流程。 */
    private class FakeMediaDownloads : MediaDownloads {
        val completeKeys = mutableSetOf<String>()
        var usedBytesValue = 0L
        var completeCountValue = 0
        var failCompleteKeys: Throwable? = null
        var failStats: Throwable? = null

        override suspend fun hasCompleteCopy(key: String): Boolean = key in completeKeys

        override suspend fun usedBytes(): Long {
            failStats?.let { throw it }
            return usedBytesValue
        }

        override suspend fun completeCount(): Int {
            failStats?.let { throw it }
            return completeCountValue
        }

        override suspend fun completeKeys(): Set<String> {
            failCompleteKeys?.let { throw it }
            return completeKeys.toSet()
        }

        override suspend fun download(
            url: String,
            key: String,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): Boolean = true

        override suspend fun remove(key: String) {
            completeKeys -= key
        }
    }

    private class FakeDownloadLedgerStore : DownloadLedgerStore {
        var entries = emptyList<DownloadEntry>()
        var failRead = false
        var failReadWithCancellation = false

        override suspend fun load(): DownloadLedger {
            if (failRead) throw RuntimeException("ledger broken")
            return DownloadLedger(entries)
        }

        override suspend fun replaceAll(entries: List<DownloadEntry>) {
            this.entries = entries
        }

        override suspend fun entriesFor(serverUrl: String, userId: String): List<DownloadEntry> {
            if (failReadWithCancellation) throw CancellationException("cancel")
            if (failRead) throw RuntimeException("ledger broken")
            return entries.filter { it.serverUrl == serverUrl && it.userId == userId }
        }
    }
}
