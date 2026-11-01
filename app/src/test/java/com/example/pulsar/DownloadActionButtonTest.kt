package com.example.pulsar

import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页下载按钮的纯逻辑（工单 05）。UI 布局本身不测（仓库没有 Compose 测试设施），
 * 这里只钉住「算下载目标 / 算要删的键 / 四态收口 / 体积估算 / 进度比例」这几条真正会算错的规则。
 */
class DownloadActionButtonTest {

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

    // ── 算下载目标 ────────────────────────────────────────────────

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

    // ── 删除：按名单删、跳过正在播的 ─────────────────────────────────

    @Test
    fun `删除只删名单里的那批，服务器新增的歌不在名单里就不删`() {
        // 名单当初只下了 t1、t2；t3 是服务器后来加的
        val keys = downloadKeysToDelete(
            trackIds = listOf("t1", "t2"),
            tracks = tracks,
            playingTrackId = null,
            streamUrl = streamUrl
        )
        assertEquals(
            listOf(
                "https://server.test/Videos/t1/stream",
                "https://server.test/Videos/t2/stream"
            ),
            keys
        )
    }

    @Test
    fun `删除跳过正在播的那首，其余照删`() {
        val keys = downloadKeysToDelete(
            trackIds = listOf("t1", "t2", "t3"),
            tracks = tracks,
            playingTrackId = "t2",
            streamUrl = streamUrl
        )
        assertFalse(keys.any { "t2" in it })
        assertEquals(2, keys.size)
    }

    @Test
    fun `删除时名单里有、曲目表里认不出的 id 忽略（歌被删了就算不出键）`() {
        val keys = downloadKeysToDelete(
            trackIds = listOf("t1", "gone"),
            tracks = tracks,
            playingTrackId = null,
            streamUrl = streamUrl
        )
        assertEquals(listOf("https://server.test/Videos/t1/stream"), keys)
    }

    // ── 四态收口 ──────────────────────────────────────────────────

    @Test
    fun `四态：下载中与部分失败照抄调度器，其余按字节真相`() {
        val downloading = DownloadState.Downloading(10, 100)
        assertEquals(downloading, resolveDownloadState(downloading, allTracksComplete = false))
        // 字节全齐也不影响进度显示（下载刚跑完的那一下）
        assertEquals(downloading, resolveDownloadState(downloading, allTracksComplete = true))

        val failed = DownloadState.PartiallyFailed(2)
        assertEquals(failed, resolveDownloadState(failed, allTracksComplete = false))

        // 字节真相优先：调度器说已下载（本次运行的旧状态）但字节不齐 → 未下载
        assertEquals(DownloadState.NotDownloaded, resolveDownloadState(DownloadState.Downloaded, false))
        assertEquals(DownloadState.Downloaded, resolveDownloadState(DownloadState.Downloaded, true))
        // 取消 / 从没跑过 → 看字节真相
        assertEquals(DownloadState.NotDownloaded, resolveDownloadState(DownloadState.Canceled, false))
        assertEquals(DownloadState.Downloaded, resolveDownloadState(DownloadState.Canceled, true))
        assertEquals(DownloadState.NotDownloaded, resolveDownloadState(null, false))
        assertEquals(DownloadState.Downloaded, resolveDownloadState(null, true))
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

    // ── 体积估算与进度比例 ─────────────────────────────────────────

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
