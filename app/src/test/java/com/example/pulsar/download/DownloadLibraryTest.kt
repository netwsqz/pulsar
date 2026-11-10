package com.example.pulsar.download

import com.example.pulsar.DownloadEntry
import com.example.pulsar.DownloadKind
import com.example.pulsar.DownloadLedger
import com.example.pulsar.DownloadLedgerStore
import com.example.pulsar.DownloadScheduler
import com.example.pulsar.DownloadState
import com.example.pulsar.DownloadTarget
import com.example.pulsar.DownloadTrack
import com.example.pulsar.MediaDownloads
import com.example.pulsar.SerializedDownloadLedgerStore
import com.example.pulsar.domain.model.Track
import com.example.pulsar.targetId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    /** 下载按钮的目标：曲目 → 缓存键与 Fake 的 `stream/{id}` 对齐。 */
    private fun target(
        tracks: List<Track> = listOf(track("t1"), track("t2"), track("t3")),
        userId: String = "user-1",
        serverUrl: String = "https://server.test",
        kind: DownloadKind = DownloadKind.ALBUM,
        id: String = "album-1",
        name: String = "专辑"
    ) = DownloadTarget(
        serverUrl = serverUrl,
        userId = userId,
        kind = kind,
        id = id,
        name = name,
        tracks = tracks.map { DownloadTrack(it.id, "stream/${it.id}", "stream/${it.id}") }
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

    // ── 五态 stateFor（#5）──────────────────────────────────────────

    @Test
    fun `stateFor：字节全齐且都在当前账号名单 → 已下载`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2", "stream/t3")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "t2", "t3")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val state = library.stateFor(target())
        runCurrent()

        assertEquals(DownloadActionState.Downloaded, state.value)
    }

    @Test
    fun `stateFor：字节完整但不在当前账号名单 → 未下载`() = runTest {
        // 盘上有完整字节（可能是别的账号 / 历史遗留），但当前账号的名单里没有它
        val downloads = FakeMediaDownloads().apply { completeKeys += "stream/t1" }
        val library = library(downloads, FakeDownloadLedgerStore())
        library.setAccount(user1)

        val state = library.stateFor(target(tracks = listOf(track("t1"))))
        runCurrent()

        assertEquals(DownloadActionState.NotDownloaded, state.value)
    }

    @Test
    fun `stateFor：换账号后旧目标 → 未下载，字节不删除`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2", "stream/t3")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "t2", "t3"), userId = "user-1"))
        }
        val library = library(downloads, ledger)
        // 当前账号是 user-2；目标还是 user-1 的（界面切换瞬间可能还挂着旧 target）
        library.setAccount(user2)

        val state = library.stateFor(target(userId = "user-1"))
        runCurrent()

        assertEquals(DownloadActionState.NotDownloaded, state.value)
        // ADR-0007：换账号不删共享字节
        assertEquals(setOf("stream/t1", "stream/t2", "stream/t3"), downloads.completeKeys)
    }

    @Test
    fun `stateFor：账号还没确定 → 检查中，不猜未下载`() = runTest {
        val downloads = FakeMediaDownloads().apply { completeKeys += "stream/t1" }
        val library = library(downloads, FakeDownloadLedgerStore())
        // 不调 setAccount：会话还没恢复出来

        val state = library.stateFor(target(tracks = listOf(track("t1"))))
        runCurrent()

        assertEquals(DownloadActionState.Checking, state.value)
    }

    @Test
    fun `stateFor：空曲目表 → 未下载，不能永远检查中`() = runTest {
        val library = library(FakeMediaDownloads(), FakeDownloadLedgerStore())
        library.setAccount(user1)

        val state = library.stateFor(target(tracks = emptyList()))
        runCurrent()

        assertEquals(DownloadActionState.NotDownloaded, state.value)
    }

    @Test
    fun `stateFor：名单未注入 → 退化成只看字节真相`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2")
        }
        val library = library(downloads, ledger = null)
        library.setAccount(user1)

        val state = library.stateFor(target(tracks = listOf(track("t1"), track("t2"))))
        runCurrent()

        assertEquals(DownloadActionState.Downloaded, state.value)
    }

    @Test
    fun `stateFor：字节层读失败 → 检查中，不把未知当未下载`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            failHasCompleteCopy = RuntimeException("cache broken")
        }
        val library = library(downloads, FakeDownloadLedgerStore())
        library.setAccount(user1)

        val state = library.stateFor(target(tracks = listOf(track("t1"))))
        runCurrent()

        assertEquals(DownloadActionState.Checking, state.value)
    }

    @Test
    fun `stateFor：调度器下载中实时映射进度，完成后按字节收口`() = runTest {
        val downloads = FakeMediaDownloads().apply { downloadGate = CompletableDeferred() }
        val ledger = FakeDownloadLedgerStore()
        val scheduler = DownloadScheduler(backgroundScope, downloads, ledger)
        val library = DownloadLibrary(downloads, ledger, scheduler, backgroundScope)
        library.setAccount(user1)
        val target = target(tracks = listOf(track("t1")))
        val state = library.stateFor(target)

        scheduler.enqueue(target)
        runCurrent()
        // 调度器本次运行的事实优先：进度环不能等到字节查完才出现
        assertEquals(DownloadActionState.Downloading(0, 0), state.value)

        downloads.downloadGate!!.complete(Unit)
        runCurrent()
        // 字节写完后回到字节真相（名单是 enqueue 时写的）
        assertEquals(DownloadActionState.Downloaded, state.value)
    }

    @Test
    fun `stateFor：部分失败直接照抄调度器，不按字节猜`() = runTest {
        // t2 每次下载都失败（重试 3 次后跳过），t1 正常完成
        val downloads = FakeMediaDownloads().apply { failKeys += "stream/t2" }
        val ledger = FakeDownloadLedgerStore()
        val scheduler = DownloadScheduler(backgroundScope, downloads, ledger)
        val library = DownloadLibrary(downloads, ledger, scheduler, backgroundScope)
        library.setAccount(user1)
        val target = target(tracks = listOf(track("t1"), track("t2")))
        val state = library.stateFor(target)

        library.enqueue(target)
        // 失败重试退避 1s/2s/4s，必须用有界时间推进；advanceUntilIdle 在常驻 collect 下推不动。
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        // 「N 首失败 · 重试」是本次运行的事实，字节真相（t1 已完整）不能把它盖成未下载
        assertEquals(DownloadActionState.PartiallyFailed(1), state.value)
        assertTrue("stream/t1" in downloads.completeKeys)
    }

    // ── 删除（#5）──────────────────────────────────────────────────

    @Test
    fun `删除只删名单里的那批，服务器新增的歌不在名单里不动`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2", "stream/t3")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "t2")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val result = library.delete(target(), playingTrackId = null)

        assertEquals(2, result.deletedCount)
        assertTrue(result.ledgerUpdated)
        assertEquals(listOf("stream/t1", "stream/t2"), downloads.removedKeys)
        // 服务器后来加进专辑的 t3 不在名单里：字节不动
        assertEquals(setOf("stream/t3"), downloads.completeKeys)
        assertTrue(ledger.entries.none { it.id == "album-1" })
    }

    @Test
    fun `删除跳过正在播的那首，名单里保留它`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2", "stream/t3")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "t2", "t3")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val result = library.delete(target(), playingTrackId = "t2")

        // 报的是真删掉的副本数：正在播的那首跳过，不算它
        assertEquals(2, result.deletedCount)
        assertEquals(listOf("stream/t1", "stream/t3"), downloads.removedKeys)
        assertTrue("stream/t2" in downloads.completeKeys)
        // 名单只留正在播的那首，下次还能删
        assertEquals(listOf("t2"), ledger.entries.single().trackIds)
    }

    @Test
    fun `删除时名单里认不出的 id 忽略，不按专辑名一锅端`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2", "stream/t3")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "gone")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val result = library.delete(target(), playingTrackId = null)

        // 认不出 "gone" 就算不出缓存键；t2 / t3 从没进过名单，也不许删
        assertEquals(1, result.deletedCount)
        assertEquals(listOf("stream/t1"), downloads.removedKeys)
        assertEquals(setOf("stream/t2", "stream/t3"), downloads.completeKeys)
    }

    @Test
    fun `名单缺失时只删当前目标里已有完整副本的那几首`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t3")
        }
        // 名单没注入：兜底只认字节，且只认 target.tracks 里的曲目
        val library = library(downloads, ledger = null)
        library.setAccount(user1)

        val result = library.delete(target(), playingTrackId = null)

        assertEquals(2, result.deletedCount)
        assertFalse(result.ledgerUpdated)
        assertEquals(listOf("stream/t1", "stream/t3"), downloads.removedKeys)
        assertTrue(downloads.completeKeys.isEmpty())
    }

    @Test
    fun `换账号后旧目标来删 → 一个字节也不删`() = runTest {
        val downloads = FakeMediaDownloads().apply { completeKeys += "stream/t1" }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1"), userId = "user-1"))
        }
        val library = library(downloads, ledger)
        library.setAccount(user2)

        val result = library.delete(target(userId = "user-1"), playingTrackId = null)

        assertEquals(0, result.deletedCount)
        assertFalse(result.ledgerUpdated)
        assertEquals(setOf("stream/t1"), downloads.completeKeys)
        assertTrue(downloads.removedKeys.isEmpty())
    }

    @Test
    fun `单曲删除：删掉字节并从名单里去掉，正在播的不动`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "t2")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)

        val deleted = library.deleteTrack(track("t2"), key = "stream/t2", playingTrackId = null)

        assertTrue(deleted)
        assertEquals(listOf("stream/t2"), downloads.removedKeys)
        assertEquals(listOf("t1"), ledger.entries.single().trackIds)

        // 正在播的那首：ViewModel 侧可以挡，module 自己也必须挡一道
        val playingDeleted = library.deleteTrack(track("t1"), key = "stream/t1", playingTrackId = "t1")
        assertFalse(playingDeleted)
        assertEquals(listOf("stream/t2"), downloads.removedKeys)
        assertEquals(listOf("t1"), ledger.entries.single().trackIds)
    }

    // ── 入队 / 取消 / 统计入口（#6）────────────────────────────────────

    @Test
    fun `入队：自动包住 scheduler，名单写一次，下载后刷新统计`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            downloadGate = CompletableDeferred()
            // 造一个「每首 8 字节」的字节层：验证下完后统计跟着刷新（真实层同理）。
            deriveStatsFromKeys = true
        }
        val ledger = FakeDownloadLedgerStore()
        val scheduler = DownloadScheduler(backgroundScope, downloads, ledger)
        val library = DownloadLibrary(downloads, ledger, scheduler, backgroundScope)
        library.setAccount(user1)
        val target = target(tracks = listOf(track("t1")))

        library.enqueue(target)
        runCurrent()

        // 入队 = scheduler.enqueue，不需要界面再拿 downloadScheduler
        assertEquals(DownloadState.Downloading(0, 0), scheduler.state.value[target.targetId])
        assertEquals(1, ledger.updateCalls)

        // t1 卡住，target 显示下载中
        assertEquals(DownloadActionState.Downloading(0, 0), library.stateFor(target).value)

        downloads.downloadGate!!.complete(Unit)
        runCurrent()

        // 下完后 statistics 刷新
        assertEquals(DownloadStats(usedBytes = 8L, completeCount = 1), library.state.value.stats)
        assertEquals(DownloadActionState.Downloaded, library.stateFor(target).value)
    }

    @Test
    fun `取消保留已下字节，再点继续只补缺口`() = runTest {
        val downloads = FakeMediaDownloads()
        downloads.gates += "stream/t2" to CompletableDeferred()
        val ledger = FakeDownloadLedgerStore()
        val scheduler = DownloadScheduler(backgroundScope, downloads, ledger)
        val library = DownloadLibrary(downloads, ledger, scheduler, backgroundScope)
        library.setAccount(user1)
        val target = target(tracks = listOf(track("t1"), track("t2")))

        library.enqueue(target)
        runCurrent()
        assertTrue("stream/t1" in downloads.completeKeys)

        library.cancel(target)
        runCurrent()

        assertTrue("取消不能回滚已下字节", "stream/t1" in downloads.completeKeys)
        assertEquals(DownloadState.Canceled, scheduler.state.value[target.targetId])

        // 再点继续：已下的不重下，只补 t2
        downloads.gates.clear()
        library.enqueue(target)
        runCurrent()

        assertEquals(listOf("stream/t1", "stream/t2", "stream/t2"), downloads.downloadCalls)
        assertEquals(DownloadState.Downloaded, scheduler.state.value[target.targetId])
        assertEquals(listOf("t1", "t2"), ledger.entries.single().trackIds)
    }

    @Test
    fun `统计随删除刷新快照`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/t1", "stream/t2")
            deriveStatsFromKeys = true
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("t1", "t2")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)
        val target = target(tracks = listOf(track("t1"), track("t2")))
        library.refresh(listOf(track("t1"), track("t2")), ::keyOf)
        assertEquals(DownloadStats(usedBytes = 16L, completeCount = 2), library.state.value.stats)

        library.delete(target, playingTrackId = null)

        assertEquals(DownloadStats(usedBytes = 0L, completeCount = 0), library.state.value.stats)
    }

    @Test
    fun `refreshStats 只重读统计，不动可见集合`() = runTest {
        val downloads = FakeMediaDownloads().apply {
            completeKeys += "stream/a"
            usedBytesValue = 1L
            completeCountValue = 1
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(entry(trackIds = listOf("a")))
        }
        val library = library(downloads, ledger)
        library.setAccount(user1)
        val before = library.refresh(listOf(track("a")), ::keyOf)
        assertEquals(setOf("a"), before.visibleTrackIds)

        // 设置页进页时只刷新占用数字：统计账号无关，不需要曲目表，也不该动可见集合。
        downloads.usedBytesValue = 9L
        downloads.completeCountValue = 2
        library.refreshStats()

        assertEquals(DownloadStats(usedBytes = 9L, completeCount = 2), library.state.value.stats)
        assertEquals(before.visibleTrackIds, library.state.value.visibleTrackIds)
    }

    @Test
    fun `两个协程并发写名单不丢条目`() = runTest {
        val backing = YieldingLedgerStore()
        val store = SerializedDownloadLedgerStore(backing)

        val downloads = FakeMediaDownloads()
        val scheduler = DownloadScheduler(backgroundScope, downloads, store)
        val library = DownloadLibrary(downloads, store, scheduler, backgroundScope)
        library.setAccount(user1)

        // 两个协程同时做「读 → 改 → 写」：只有共用同一把锁才不会互相覆盖
        launch {
            library.updateLedger { entries ->
                entries + entry(trackIds = listOf("a"), id = "album-a")
            }
        }
        launch {
            library.updateLedger { entries ->
                entries + entry(trackIds = listOf("b"), id = "album-b")
            }
        }
        advanceUntilIdle()

        assertEquals(2, backing.entries.size)
        assertEquals(setOf("a", "b"), backing.entries.flatMap { it.trackIds }.toSet())
    }

    // ── 串行化存储（#6 Step 4）─────────────────────────────────────

    private class YieldingLedgerStore : DownloadLedgerStore {
        var entries = emptyList<DownloadEntry>()
        var updateCalls = 0

        override suspend fun load(): DownloadLedger {
            yield()
            return DownloadLedger(entries)
        }

        override suspend fun replaceAll(entries: List<DownloadEntry>) {
            yield()
            this.entries = entries
        }

        override suspend fun entriesFor(serverUrl: String, userId: String): List<DownloadEntry> =
            entries.filter { it.serverUrl == serverUrl && it.userId == userId }

        override suspend fun pruneUnattributed(): Int {
            val current = load()
            val kept = current.entries.filter { it.serverUrl.isNotBlank() }
            val removed = current.entries.size - kept.size
            if (removed > 0) replaceAll(kept)
            return removed
        }

        override suspend fun update(block: suspend (List<DownloadEntry>) -> List<DownloadEntry>): Unit {
            updateCalls++
            // 读-改-写之间刻意 yield：没有串行化就会丢更新
            this.entries = block(this.entries)
        }
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
        val removedKeys = mutableListOf<String>()
        val downloadCalls = mutableListOf<String>()
        /** 这些 key 的下载永远失败（用来造「部分失败」）。 */
        val failKeys = mutableSetOf<String>()
        /** key → 下载开始前卡住的闸门，用来复现「取消到一半」。 */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var usedBytesValue = 0L
        var completeCountValue = 0
        var failCompleteKeys: Throwable? = null
        var failHasCompleteCopy: Throwable? = null
        var failStats: Throwable? = null
        /** 非空时 [download] 真的挂起，用来把调度器钉在「下载中」。 */
        var downloadGate: CompletableDeferred<Unit>? = null
        /** true = 统计按 [completeKeys] 推导（每首 8 字节），用来验证完成/删除后的刷新。 */
        var deriveStatsFromKeys = false
        var activeDownloads = 0
        var maxActiveDownloads = 0

        override suspend fun hasCompleteCopy(key: String): Boolean {
            failHasCompleteCopy?.let { throw it }
            return key in completeKeys
        }

        override suspend fun usedBytes(): Long {
            failStats?.let { throw it }
            return if (deriveStatsFromKeys) completeKeys.size * 8L else usedBytesValue
        }

        override suspend fun completeCount(): Int {
            failStats?.let { throw it }
            return if (deriveStatsFromKeys) completeKeys.size else completeCountValue
        }

        override suspend fun completeKeys(): Set<String> {
            failCompleteKeys?.let { throw it }
            return completeKeys.toSet()
        }

        override suspend fun download(
            url: String,
            key: String,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): Boolean {
            downloadCalls += key
            if (key in failKeys) return false
            activeDownloads++
            maxActiveDownloads = maxOf(maxActiveDownloads, activeDownloads)
            try {
                gates[key]?.await()
                downloadGate?.await()
                completeKeys += key
                onProgress(1L, 1L)
            } finally {
                activeDownloads--
            }
            return true
        }

        override suspend fun remove(key: String) {
            removedKeys += key
            completeKeys -= key
        }
    }

    private class FakeDownloadLedgerStore : DownloadLedgerStore {
        var entries = emptyList<DownloadEntry>()
        var failRead = false
        var failReadWithCancellation = false
        var updateCalls = 0

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

        override suspend fun pruneUnattributed(): Int {
            val current = load()
            val kept = current.entries.filter { it.serverUrl.isNotBlank() }
            val removed = current.entries.size - kept.size
            if (removed > 0) replaceAll(kept)
            return removed
        }

        override suspend fun update(block: suspend (List<DownloadEntry>) -> List<DownloadEntry>): Unit {
            updateCalls++
            this.entries = block(this.entries)
        }
    }
}
