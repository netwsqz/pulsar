package com.example.pulsar.download

import com.example.pulsar.DownloadKind
import com.example.pulsar.DownloadState
import com.example.pulsar.DownloadTarget
import com.example.pulsar.DownloadTargetId
import com.example.pulsar.DownloadTrack
import com.example.pulsar.downloadProgressFraction
import com.example.pulsar.estimateDownloadBytes
import com.example.pulsar.formatBytes
import com.example.pulsar.targetId
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载按钮纯逻辑（原 `DownloadActionButtonTest` 的迁移版，#5）。
 *
 * UI 布局本身依旧不测（仓库没有 Compose 测试设施），这里只钉住「算下载目标 / 五态收口 /
 * 字节真相三态 / 删除提示 / 状态签名 / 体积估算 / 进度比例」这些会算错规则。
 * 删除的**行为**（按名单删、跳过正在播、名单兜底）在 [DownloadLibraryTest] 里用真 module 测。
 */
class DownloadActionStateTest {

    private fun track(
        id: String,
        durationMs: Long? = 240_000L,
        album: String = "专辑 A"
    ) = Track(
        id = id,
        title = "曲目 $id",
        artist = "歌手",
        album = album,
        durationMs = durationMs,
        imageTag = null
    )

    private val tracks = listOf(track("t1"), track("t2"), track("t3"))

    /** 测试用的 streamUrl：跟生产一样「算不出就返回 null」。 */
    private val streamUrl: (Track) -> String? = { t -> "https://server.test/Videos/${t.id}/stream" }

    // ── 算下载目标（从 UI 文件迁到 download 包）──────────────────────

    @Test
    fun `下载目标：url 与 key 都取播放 URL（token 不进 URL）`() {
        val target = buildDownloadTarget(
            serverUrl = "https://server.test",
            userId = "user-1",
            kind = DownloadKind.ALBUM,
            id = "album-a",
            name = "专辑 A",
            tracks = tracks,
            streamUrl = streamUrl
        )
        checkNotNull(target)
        assertEquals(listOf("t1", "t2", "t3"), target.tracks.map { it.id })
        assertEquals(target.tracks.map { it.url }, target.tracks.map { it.key })
        assertTrue(target.tracks.all { "token" !in it.url })
        assertEquals(DownloadKind.ALBUM, target.kind)
        assertEquals("user-1", target.userId)
        assertEquals(DownloadTargetId(DownloadKind.ALBUM, "album-a"), target.targetId)
    }

    @Test
    fun `下载目标：算不出 URL 的曲目跳过，一首都算不出返回 null`() {
        val partial = buildDownloadTarget(
            serverUrl = "https://server.test",
            userId = "user-1",
            kind = DownloadKind.PLAYLIST,
            id = "pl-1",
            name = "列表",
            tracks = tracks,
            streamUrl = { t -> if (t.id == "t2") null else streamUrl(t) }
        )
        assertEquals(listOf("t1", "t3"), partial?.tracks?.map { it.id })

        // 未登录 / 空专辑：没有可下的东西 → null（按钮禁用）
        assertNull(
            buildDownloadTarget(
                "https://server.test", "user-1", DownloadKind.ALBUM, "album-a", "专辑 A",
                tracks, streamUrl = { null }
            )
        )
        assertNull(
            buildDownloadTarget(
                "https://server.test", "user-1", DownloadKind.ALBUM, "album-a", "专辑 A",
                emptyList(), streamUrl
            )
        )
        // 没有会话（userId / serverUrl 空）：名单写不了归属，不做目标
        assertNull(
            buildDownloadTarget(
                "https://server.test", "", DownloadKind.ALBUM, "album-a", "专辑 A",
                tracks, streamUrl
            )
        )
        assertNull(
            buildDownloadTarget(
                "", "user-1", DownloadKind.ALBUM, "album-a", "专辑 A",
                tracks, streamUrl
            )
        )
    }

    // ── 五态收口（工单 13 / #5）────────────────────────────────────

    @Test
    fun `五态：下载中与部分失败照抄调度器，其余按字节真相`() {
        val downloading = DownloadState.Downloading(10, 100)
        assertEquals(
            DownloadActionState.Downloading(10, 100),
            resolveDownloadActionState(downloading, allTracksComplete = false)
        )
        // 字节全齐也不影响进度显示（下载刚跑完的那一下）
        assertEquals(
            DownloadActionState.Downloading(10, 100),
            resolveDownloadActionState(downloading, allTracksComplete = true)
        )
        // 字节还没查到也照抄：下载中优先于「检查中…」，否则进度环会被顶掉
        assertEquals(
            DownloadActionState.Downloading(10, 100),
            resolveDownloadActionState(downloading, allTracksComplete = null)
        )

        val failed = DownloadState.PartiallyFailed(2)
        assertEquals(
            DownloadActionState.PartiallyFailed(2),
            resolveDownloadActionState(failed, allTracksComplete = false)
        )
        assertEquals(
            DownloadActionState.PartiallyFailed(2),
            resolveDownloadActionState(failed, allTracksComplete = true)
        )
        assertEquals(
            DownloadActionState.PartiallyFailed(2),
            resolveDownloadActionState(failed, allTracksComplete = null)
        )

        // 字节真相优先：调度器说已下载（本次运行的旧状态）但字节不齐 → 未下载
        assertEquals(
            DownloadActionState.NotDownloaded,
            resolveDownloadActionState(DownloadState.Downloaded, false)
        )
        assertEquals(
            DownloadActionState.Downloaded,
            resolveDownloadActionState(DownloadState.Downloaded, true)
        )
        // 取消 / 从没跑过 → 看字节真相
        assertEquals(
            DownloadActionState.NotDownloaded,
            resolveDownloadActionState(DownloadState.Canceled, false)
        )
        assertEquals(
            DownloadActionState.Downloaded,
            resolveDownloadActionState(DownloadState.Canceled, true)
        )
        assertEquals(
            DownloadActionState.NotDownloaded,
            resolveDownloadActionState(null, false)
        )
        assertEquals(
            DownloadActionState.Downloaded,
            resolveDownloadActionState(null, true)
        )
    }

    @Test
    fun `五态：字节还没查完（null）就是未知态，不猜「未下载」`() {
        // 用户故事 59：打开已下载专辑时先给「检查中…」，不先闪一个可点的「下载」
        assertEquals(
            DownloadActionState.Checking,
            resolveDownloadActionState(null, allTracksComplete = null)
        )
        assertEquals(
            DownloadActionState.Checking,
            resolveDownloadActionState(DownloadState.NotDownloaded, allTracksComplete = null)
        )
        assertEquals(
            DownloadActionState.Checking,
            resolveDownloadActionState(DownloadState.Downloaded, allTracksComplete = null)
        )
        assertEquals(
            DownloadActionState.Checking,
            resolveDownloadActionState(DownloadState.Canceled, allTracksComplete = null)
        )
    }

    @Test
    fun `五态：账号不一致时一律未下载，连进度也不显示`() {
        assertEquals(
            DownloadActionState.NotDownloaded,
            resolveDownloadActionState(
                live = DownloadState.Downloading(1, 100),
                allTracksComplete = true,
                accountMatches = false
            )
        )
        assertEquals(
            DownloadActionState.NotDownloaded,
            resolveDownloadActionState(
                live = DownloadState.PartiallyFailed(1),
                allTracksComplete = null,
                accountMatches = false
            )
        )
    }

    @Test
    fun `字节真相：会话没读出来是未知，target 为空或空专辑收口为未下载`() = runTest {
        val found = DownloadTarget(
            serverUrl = "https://server.test",
            userId = "user-1",
            kind = DownloadKind.ALBUM,
            id = "album-a",
            name = "专辑 A",
            tracks = tracks.map { DownloadTrack(it.id, streamUrl(it)!!, streamUrl(it)!!) }
        )
        val empty = found.copy(tracks = emptyList())

        // 会话还没读出来：不知道这份字节属于谁，保持未知（按钮显示「检查中…」）
        assertNull(resolveAllTracksComplete(null, sessionResolved = false) { true })
        assertNull(resolveAllTracksComplete(found, sessionResolved = false) { true })

        // 会话已就绪但没有可下的目标（未登录 / 空专辑 / 算不出 URL）：收口为未下载，不能永远检查中
        assertFalse(resolveAllTracksComplete(null, sessionResolved = true) { true }!!)
        assertFalse(resolveAllTracksComplete(empty, sessionResolved = true) { true }!!)

        // 照抄字节真相：全齐 true，缺一首 false
        assertTrue(resolveAllTracksComplete(found, sessionResolved = true) { true }!!)
        assertFalse(
            resolveAllTracksComplete(found, sessionResolved = true) { it.endsWith("t2") }!!
        )
    }

    // ── 删除结果文案（用户故事 62）────────────────────────────────────

    @Test
    fun `删除提示：删掉几首说几首，一首没删就不打扰`() {
        assertEquals("已删除 3 首", downloadDeletedMessage(3))
        assertNull(downloadDeletedMessage(0))
        assertNull(downloadDeletedMessage(-1))
    }

    @Test
    fun `状态签名只在种类变化时改（进度回调不触发重算）`() {
        assertEquals(
            downloadStateSignature(DownloadState.Downloading(1, 100)),
            downloadStateSignature(DownloadState.Downloading(90, 100))
        )
        assertEquals("failed:2", downloadStateSignature(DownloadState.PartiallyFailed(2)))
        assertEquals("downloaded", downloadStateSignature(DownloadState.Downloaded))
        assertEquals("none", downloadStateSignature(null))
    }

    // ── 体积估算与进度比例（UI 侧辅助，留在 UI 文件）────────────────

    @Test
    fun `体积估算按 320kbps 折算`() {
        // 每首 240s × 40 KB/s = 9.6 MB；三首 ≈ 28.8 MB → formatBytes 显示 29 MB
        assertEquals(28_800_000L, estimateDownloadBytes(tracks))
        assertEquals("29 MB", formatBytes(estimateDownloadBytes(tracks)))
        // 时长未知的曲目按 0 算，不炸
        assertEquals(0L, estimateDownloadBytes(listOf(track("x", durationMs = null))))
        assertEquals(0L, estimateDownloadBytes(emptyList()))
    }

    @Test
    fun `进度比例：总长未知返回 null，其余夹在 0 到 1`() {
        assertNull(downloadProgressFraction(DownloadState.Downloading(0, 0)))
        assertNull(downloadProgressFraction(DownloadState.Downloading(5, -1)))
        assertEquals(0.5f, downloadProgressFraction(DownloadState.Downloading(50, 100)))
        assertEquals(1f, downloadProgressFraction(DownloadState.Downloading(200, 100)))
    }
}
