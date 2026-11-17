package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.OfflineInput
import com.example.pulsar.offline.offlineView
import com.example.pulsar.state.LibraryState
import com.example.pulsar.state.SearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选「全选」的目标列表必须**等于界面当前看得见的那批**（#30）。
 *
 * 离线时列表只显示已下载（ADR-0008），若全选取原始全量，会把看不见的歌勾进删除集合。
 * 这里钉死：全选源一律取 [VisibleLibrary]（已过离线策略），不再读原始 state；
 * 发现页则只取页面真正渲染的每日推荐。
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

    private fun offlineVisible(
        visibleIds: Set<String>,
        albumTracks: List<Track> = all,
        playlistTracks: List<Track> = all,
        searchResults: SearchResults? = null
    ) = offlineVisibleLibrary(
        tracks = all,
        albums = emptyList(),
        albumTracks = albumTracks,
        playlistTracks = playlistTracks,
        searchResults = searchResults,
        visibleTrackIds = visibleIds
    )

    @Test
    fun `歌曲页全选取可见曲目`() {
        val visible = offlineVisible(setOf("a", "c"))

        val ids = currentListTracks(LibrarySection.Tracks, visible, emptyList()).map { it.id }

        assertEquals(listOf("a", "c"), ids)
    }

    @Test
    fun `发现页全选只勾每日推荐 不是整个曲库`() {
        // 发现页只有「每日推荐」那几行是可勾选的曲目行；全量曲库在页面上根本看不见。
        val visible = offlineVisible(setOf("a"))
        val dailyPicks = listOf(track("b"))

        val ids = currentListTracks(LibrarySection.Discover, visible, dailyPicks).map { it.id }

        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `专辑详情与播放列表详情全选只取各自可见曲目`() {
        // 两份列表内容不同，才能分辨实现读的是哪一个分支。
        val visible = offlineVisible(
            visibleIds = setOf("a", "c"),
            albumTracks = listOf(track("a"), track("b")),
            playlistTracks = listOf(track("b"), track("c"))
        )

        assertEquals(
            listOf("a"),
            currentListTracks(LibrarySection.AlbumDetail(album), visible, emptyList()).map { it.id }
        )
        assertEquals(
            listOf("c"),
            currentListTracks(LibrarySection.PlaylistDetail(playlist), visible, emptyList()).map { it.id }
        )
    }

    @Test
    fun `搜索页全选取离线过滤后的搜索结果`() {
        val visible = offlineVisible(
            visibleIds = setOf("a"),
            searchResults = SearchResults(
                tracks = all,
                albums = emptyList(),
                playlists = emptyList()
            )
        )

        val ids = currentListTracks(LibrarySection.Search, visible, emptyList()).map { it.id }

        assertEquals(listOf("a"), ids)
    }

    @Test
    fun `离线策略输出直接喂给全选时只勾已下载`() {
        // 调用点真正传的是 offlineView(...).visibleLibrary；这里把整条链路走一遍，
        // 防止实现改回读原始 state（单测只看 VisibleLibrary 的话，调用点接错不会红）。
        val view = offlineView(
            OfflineInput(
                isOffline = true,
                downloadedTrackIds = setOf("a", "c"),
                tracks = all,
                albums = emptyList(),
                discoverAlbums = emptyList(),
                albumTracks = all,
                playlistTracks = all,
                searchResults = null,
                tracksLoaded = true,
                isLoading = false
            )
        )

        val ids = currentListTracks(LibrarySection.Tracks, view.visibleLibrary, emptyList()).map { it.id }

        assertEquals(listOf("a", "c"), ids)
    }

    @Test
    fun `在线时全选行为不变 仍是全量`() {
        val visible = onlineVisible()

        assertEquals(all, currentListTracks(LibrarySection.Tracks, visible, emptyList()))
    }

    @Test
    fun `没有曲目行的页面全选为空`() {
        val visible = onlineVisible()

        assertTrue(currentListTracks(LibrarySection.Settings, visible, emptyList()).isEmpty())
        assertTrue(currentListTracks(LibrarySection.CachedCopies, visible, emptyList()).isEmpty())
    }

    @Test
    fun `离线收缩后删除候选池仍包含已勾选的原始曲目`() {
        // 场景：在线时勾了 a、b，随后断网，可见列表只剩已下载的 a。
        // 删除目标若从可见列表取会丢掉 b——条上写着「已选 2 首」，确认框却只删 1 首，
        // 极端情况（一首都没下载）就是点了删除毫无反应。
        val library = LibraryState(tracks = all)
        val visible = offlineVisible(setOf("a"))
        val selected = setOf("a", "b")

        val fromVisible = visible.tracks.orEmpty().filter { it.id in selected }
        val targets = deleteCandidateTracks(LibrarySection.Tracks, library).filter { it.id in selected }

        assertEquals(listOf("a"), fromVisible.map { it.id })
        assertEquals(listOf("a", "b"), targets.map { it.id })
    }

    @Test
    fun `删除候选池按页面取原始列表`() {
        val albumOnly = listOf(track("a"), track("b"))
        val playlistOnly = listOf(track("b"), track("c"))
        val searchOnly = listOf(track("c"))
        val library = LibraryState(
            tracks = all,
            albumTracks = albumOnly,
            playlistTracks = playlistOnly,
            search = SearchState(
                results = SearchResults(
                    tracks = searchOnly,
                    albums = emptyList(),
                    playlists = emptyList()
                )
            )
        )

        assertEquals(albumOnly, deleteCandidateTracks(LibrarySection.AlbumDetail(album), library))
        assertEquals(playlistOnly, deleteCandidateTracks(LibrarySection.PlaylistDetail(playlist), library))
        assertEquals(searchOnly, deleteCandidateTracks(LibrarySection.Search, library))
        assertEquals(all, deleteCandidateTracks(LibrarySection.Discover, library))
        assertTrue(deleteCandidateTracks(LibrarySection.Settings, library).isEmpty())
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
