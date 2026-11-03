package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** 专辑详情摘要（工单 10）：与播放列表同一口径——计数跟列表实际行数同源，离线写「已下载 N 首 · 共 M 首」。 */
class AlbumSummaryTest {

    private fun album(trackCount: Int?) =
        Album(id = "a1", name = "专辑", artist = "歌手", trackCount = trackCount, imageTag = null)

    private fun tracks(count: Int, durationMs: Long = 180_000L): List<Track> = List(count) { index ->
        Track(
            id = "t$index",
            title = "歌 $index",
            artist = "歌手",
            album = "专辑",
            durationMs = durationMs,
            imageTag = null
        )
    }

    @Test
    fun `在线时曲目已加载用列表长度加时长`() {
        assertEquals(
            "3 首 · 9 分钟",
            albumSummary(album(trackCount = 10), tracks(3), isOfflineView = false)
        )
    }

    @Test
    fun `在线时时长未知就只显示曲目数`() {
        assertEquals(
            "2 首",
            albumSummary(album(trackCount = 10), tracks(2, durationMs = 0L), isOfflineView = false)
        )
    }

    @Test
    fun `在线时曲目已加载但为空显示 0 首`() {
        assertEquals(
            "0 首",
            albumSummary(album(trackCount = 5), emptyList(), isOfflineView = false)
        )
    }

    @Test
    fun `曲目未加载时退回服务端计数`() {
        assertEquals("7 首", albumSummary(album(trackCount = 7), tracks = null, isOfflineView = false))
    }

    @Test
    fun `曲目和服务端计数都未知时显示专辑`() {
        assertEquals("专辑", albumSummary(album(trackCount = null), tracks = null, isOfflineView = false))
    }

    @Test
    fun `离线时按可见曲目计数并给出服务端总数`() {
        assertEquals(
            "已下载 4 首 · 共 10 首",
            albumSummary(album(trackCount = 10), tracks(4), isOfflineView = true)
        )
    }

    @Test
    fun `离线上限不超过服务端总数时总数用服务端值`() {
        // 服务端计数滞后（比实际可见还少）时不显示对不上的摘要。
        assertEquals(
            "已下载 4 首 · 共 4 首",
            albumSummary(album(trackCount = 3), tracks(4), isOfflineView = true)
        )
    }

    @Test
    fun `离线服务端计数未知时总数取可见数`() {
        assertEquals(
            "已下载 4 首 · 共 4 首",
            albumSummary(album(trackCount = null), tracks(4), isOfflineView = true)
        )
    }

    @Test
    fun `离线曲目还没加载时退回服务端计数`() {
        assertEquals(
            "10 首",
            albumSummary(album(trackCount = 10), tracks = null, isOfflineView = true)
        )
    }

    @Test
    fun `离线时摘要不带时长`() {
        // 离线摘要只讲「有多少下载了 / 一共多少」，不混时长。
        assertEquals(
            "已下载 4 首 · 共 10 首",
            albumSummary(album(trackCount = 10), tracks(4, durationMs = 300_000L), isOfflineView = true)
        )
    }
}
