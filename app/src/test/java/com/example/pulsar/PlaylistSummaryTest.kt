package com.example.pulsar

import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放列表详情摘要（工单 09）：计数必须跟列表实际行数同源，离线按可见曲目 + 服务端总数。 */
class PlaylistSummaryTest {

    private fun playlist(trackCount: Int?) =
        Playlist(id = "p1", name = "通勤", trackCount = trackCount, imageTag = null)

    private fun tracks(count: Int): List<Track> = List(count) { index ->
        Track(
            id = "t$index",
            title = "歌 $index",
            artist = "歌手",
            album = "专辑",
            durationMs = 180_000L,
            imageTag = null
        )
    }

    @Test
    fun `在线时曲目已加载用列表长度而不是服务端计数`() {
        assertEquals(
            "3 首",
            playlistSummary(playlist(trackCount = 10), tracks(3), isOfflineView = false)
        )
    }

    @Test
    fun `在线时曲目已加载但为空显示 0 首`() {
        assertEquals(
            "0 首",
            playlistSummary(playlist(trackCount = 5), emptyList(), isOfflineView = false)
        )
    }

    @Test
    fun `曲目未加载时退回服务端计数`() {
        assertEquals(
            "7 首",
            playlistSummary(playlist(trackCount = 7), tracks = null, isOfflineView = false)
        )
    }

    @Test
    fun `曲目和服务端计数都未知时显示播放列表`() {
        assertEquals(
            "播放列表",
            playlistSummary(playlist(trackCount = null), tracks = null, isOfflineView = false)
        )
    }

    @Test
    fun `离线时按可见曲目计数并给出服务端总数`() {
        assertEquals(
            "已下载 4 首 · 共 10 首",
            playlistSummary(playlist(trackCount = 10), tracks(4), isOfflineView = true)
        )
    }

    @Test
    fun `离线上限不超过服务端总数时总数用服务端值`() {
        // 服务端计数滞后（比实际可见还少）时不显示「共 3 首」这种对不上的摘要。
        assertEquals(
            "已下载 4 首 · 共 4 首",
            playlistSummary(playlist(trackCount = 3), tracks(4), isOfflineView = true)
        )
    }

    @Test
    fun `离线服务端计数未知时总数取可见数`() {
        assertEquals(
            "已下载 4 首 · 共 4 首",
            playlistSummary(playlist(trackCount = null), tracks(4), isOfflineView = true)
        )
    }

    @Test
    fun `离线曲目还没加载时退回服务端计数`() {
        // 离线打开已下载列表时曲目还在读本地清单：先显示服务端总数，别闪「已下载 0 首」。
        assertEquals(
            "10 首",
            playlistSummary(playlist(trackCount = 10), tracks = null, isOfflineView = true)
        )
    }

    @Test
    fun `离线曲目和服务端计数都未知时显示播放列表`() {
        assertEquals(
            "播放列表",
            playlistSummary(playlist(trackCount = null), tracks = null, isOfflineView = true)
        )
    }
}
