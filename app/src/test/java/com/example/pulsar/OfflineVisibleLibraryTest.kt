package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线可见性（ADR-0008）：离线时曲库各屏只显示**已下载**的东西，不再「全量 + 标灰」。
 *
 * 纯函数、不碰 IO：界面层把原始数据 + 已下载 id 集合喂进来，拿一份可以直接渲染的列表。
 */
class OfflineVisibleLibraryTest {
    private val a1 = Track("t1", "A1", "Artist X", "Album A", 1000L, null)
    private val a2 = Track("t2", "A2", "Artist X", "Album A", 1000L, null)
    private val b1 = Track("t3", "B1", "Artist Y", "Album B", 1000L, null)
    private val albumA = Album("al-a", "Album A", "Artist X", trackCount = 2, imageTag = null)
    private val albumB = Album("al-b", "Album B", "Artist Y", trackCount = 1, imageTag = null)

    @Test
    fun `只保留已下载的曲目，顺序不变`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, a2, b1),
            albums = listOf(albumA, albumB),
            visibleTrackIds = setOf("t1", "t3")
        )
        assertEquals(listOf(a1, b1), visible.tracks)
    }

    @Test
    fun `有一首已下载的专辑就保留整张卡片，一首都没有的不显示`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, a2, b1),
            albums = listOf(albumA, albumB),
            visibleTrackIds = setOf("t2")
        )
        // 专辑 A 只下了 1 首，卡片照样在（选曲下载不该让专辑消失）；专辑 B 一首没下，不显示。
        assertEquals(listOf(albumA), visible.albums)
    }

    @Test
    fun `专辑详情只列已下载曲目，一张专辑都没下完时也不会报空`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1),
            albums = listOf(albumA),
            albumTracks = listOf(a1, a2),
            visibleTrackIds = setOf("t1")
        )
        assertEquals(listOf(a1), visible.albumTracks)
    }

    @Test
    fun `播放列表详情只列已下载曲目`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1),
            albums = listOf(albumA),
            playlistTracks = listOf(a1, a2, b1),
            visibleTrackIds = setOf("t3")
        )
        assertEquals(listOf(b1), visible.playlistTracks)
    }

    @Test
    fun `随机专辑墙只留还有已下载曲目的专辑`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, b1),
            albums = listOf(albumA, albumB),
            discoverAlbums = listOf(albumA, albumB),
            visibleTrackIds = setOf("t3")
        )
        assertEquals(listOf(albumB), visible.discoverAlbums)
    }

    @Test
    fun `离线搜索结果只留已下载的曲目与专辑`() {
        val results = SearchResults(
            tracks = listOf(a1, b1),
            albums = listOf(albumA, albumB)
        )
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, b1),
            albums = listOf(albumA, albumB),
            searchResults = results,
            visibleTrackIds = setOf("t1")
        )
        assertEquals(SearchResults(tracks = listOf(a1), albums = listOf(albumA)), visible.searchResults)
    }

    @Test
    fun `一首都没下载时所有列表都是空的，而不是 null`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, b1),
            albums = listOf(albumA, albumB),
            albumTracks = listOf(a1),
            playlistTracks = listOf(b1),
            searchResults = SearchResults(tracks = listOf(a1), albums = listOf(albumA)),
            visibleTrackIds = emptySet()
        )
        assertTrue(visible.tracks.orEmpty().isEmpty())
        assertTrue(visible.albums.orEmpty().isEmpty())
        assertTrue(visible.discoverAlbums.orEmpty().isEmpty())
        assertTrue(visible.albumTracks.orEmpty().isEmpty())
        assertTrue(visible.playlistTracks.orEmpty().isEmpty())
        assertEquals(SearchResults(), visible.searchResults)
    }

    @Test
    fun `数据还没拉到（null）时保持 null，不把骨架屏换成空态`() {
        val visible = offlineVisibleLibrary(
            tracks = null,
            albums = null,
            albumTracks = null,
            playlistTracks = null,
            searchResults = null,
            visibleTrackIds = emptySet()
        )
        assertNull(visible.tracks)
        assertNull(visible.albums)
        assertNull(visible.albumTracks)
        assertNull(visible.playlistTracks)
        assertNull(visible.searchResults)
    }

    @Test
    fun `专辑名大小写或空白不同也匹配到同一张专辑`() {
        val variant = Track("t4", "A3", "Artist X", " album a ", 1000L, null)
        val visible = offlineVisibleLibrary(
            tracks = listOf(variant),
            albums = listOf(albumA),
            visibleTrackIds = setOf("t4")
        )
        // 按专辑身份匹配（去空白 + 大小写不敏感），不再是逐字符相等。
        assertEquals(listOf(albumA), visible.albums)
    }

    @Test
    fun `反推的专辑卡按归一化名去重`() {
        val variant = Track("t4", "A3", "Artist X", "album a", 1000L, null)
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, variant),
            albums = null,
            visibleTrackIds = setOf("t1", "t4")
        )
        assertEquals(listOf("Album A"), visible.albums?.map { it.name })
    }

    @Test
    fun `空白专辑名不反推专辑卡`() {
        val blankAlbum = Track("t9", "Song Z", "Artist X", "   ", 1000L, null)
        val visible = offlineVisibleLibrary(
            tracks = listOf(blankAlbum),
            albums = null,
            visibleTrackIds = setOf("t9")
        )
        assertNull(visible.albums)
    }

    @Test
    fun `专辑列表没拉到（null）时从已下载曲目反推专辑卡片`() {
        // 回归：登录只写曲目表、快照里没有专辑、用户也没逛过专辑页 → state.library.albums 为 null。
        // 之前这里直接返回 null，专辑页显示「手机里还没有下载的专辑」，而歌曲页明明有歌。
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, a2, b1),
            albums = null,
            visibleTrackIds = setOf("t1", "t3")
        )
        assertEquals(
            listOf(
                Album(id = "Album A", name = "Album A", artist = "Artist X", trackCount = null, imageTag = null),
                Album(id = "Album B", name = "Album B", artist = "Artist Y", trackCount = null, imageTag = null)
            ),
            visible.albums
        )
    }

    @Test
    fun `专辑列表是空列表时同样反推，不会显示成「一首都没下载」`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, b1),
            albums = emptyList(),
            visibleTrackIds = setOf("t1", "t3")
        )
        assertEquals(listOf("Album A", "Album B"), visible.albums?.map { it.name })
    }

    @Test
    fun `专辑列表里配不上的专辑也靠反推补回来（快照过旧）`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1, b1),
            albums = listOf(albumA),
            visibleTrackIds = setOf("t1", "t3")
        )
        // 专辑 A 走真对象，专辑 B 不在列表里 → 反推补上，而不是丢掉。
        assertEquals(listOf("Album A", "Album B"), visible.albums?.map { it.name })
    }

    @Test
    fun `一首都没下载且专辑列表为空时仍是空列表，不是 null`() {
        val visible = offlineVisibleLibrary(
            tracks = listOf(a1),
            albums = emptyList(),
            visibleTrackIds = emptySet()
        )
        assertEquals(emptyList<Album>(), visible.albums)
    }
}
