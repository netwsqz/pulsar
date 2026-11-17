package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选「全选」的目标列表必须**等于界面当前看得见的那批**（#30）。
 *
 * 离线时列表只显示已下载（ADR-0008），若全选取原始全量，会把看不见的歌勾进删除集合。
 * 这里钉死：全选源一律取 [VisibleLibrary]（已过离线策略），不再读原始 state。
 */
class SelectableTracksTest {

    private fun track(id: String) = Track(
        id = id,
        title = "歌曲-$id",
        artist = "歌手",
        album = "专辑",
        durationMs = 0L,
        imageTag = null
    )

    private val all = listOf(track("a"), track("b"), track("c"))

    private fun onlineVisible(tracks: List<Track> = all) = VisibleLibrary(
        tracks = tracks,
        albums = emptyList(),
        discoverAlbums = emptyList(),
        albumTracks = tracks,
        playlistTracks = tracks,
        searchResults = null
    )

    private fun offlineVisible(visibleIds: Set<String>) = offlineVisibleLibrary(
        tracks = all,
        albums = emptyList(),
        albumTracks = all,
        playlistTracks = all,
        visibleTrackIds = visibleIds
    )

    @Test
    fun `歌曲页全选取可见曲目`() {
        val visible = offlineVisible(setOf("a", "c"))

        val ids = currentListTracks(LibrarySection.Tracks, visible).map { it.id }

        assertEquals(listOf("a", "c"), ids)
    }

    @Test
    fun `发现页全选取可见曲目`() {
        val visible = offlineVisible(setOf("b"))

        val ids = currentListTracks(LibrarySection.Discover, visible).map { it.id }

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `专辑详情与播放列表详情全选只取各自可见曲目`() {
        val visible = offlineVisible(setOf("a"))

        assertEquals(
            listOf("a"),
            currentListTracks(LibrarySection.AlbumDetail(album), visible).map { it.id }
        )
        assertEquals(
            listOf("a"),
            currentListTracks(LibrarySection.PlaylistDetail(playlist), visible).map { it.id }
        )
    }

    @Test
    fun `搜索页全选取离线过滤后的搜索结果`() {
        val visible = VisibleLibrary(
            tracks = null,
            albums = null,
            discoverAlbums = null,
            albumTracks = null,
            playlistTracks = null,
            searchResults = SearchResults(
                tracks = listOf(track("a")),
                albums = emptyList(),
                playlists = emptyList()
            )
        )

        val ids = currentListTracks(LibrarySection.Search, visible).map { it.id }

        assertEquals(listOf("a"), ids)
    }

    @Test
    fun `在线时全选行为不变 仍是全量`() {
        val visible = onlineVisible()

        assertEquals(all, currentListTracks(LibrarySection.Tracks, visible))
    }

    @Test
    fun `没有曲目行的页面全选为空`() {
        val visible = onlineVisible()

        assertTrue(currentListTracks(LibrarySection.Settings, visible).isEmpty())
        assertTrue(currentListTracks(LibrarySection.CachedCopies, visible).isEmpty())
    }

    private val album = Album(
        id = "album-1",
        name = "专辑",
        artist = "歌手",
        trackCount = null,
        imageTag = null
    )

    private val playlist = Playlist(
        id = "playlist-1",
        name = "列表",
        trackCount = null,
        imageTag = null
    )
}
