package com.example.pulsar

import com.example.pulsar.data.libraryfiles.LibraryRecycleFile
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.state.DownloadsState
import com.example.pulsar.state.FileOpsState
import com.example.pulsar.state.LibrarySectionKey
import com.example.pulsar.state.LibraryState
import com.example.pulsar.state.SearchState
import com.example.pulsar.state.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 迁移期形状钉子（`docs/library-state-refactor.md` Step 1 / #11）。
 *
 * 两件事必须一直成立，否则「屏幕读旧字段、ViewModel 写新 slice」会在迁移期读到过期值：
 * 1. **旧写法 → slice**：`state.copy(旧字段名 = v)` 之后，`state.旧字段名` 与对应 slice 字段都等于 v；
 * 2. **slice 写法 → 旧读法**：直接 `copy(session/library/downloads = ...)` 之后，旧字段读法同步。
 *
 * 每个字段都要被这两条覆盖；新增字段时补在这里（直到 #16 删掉兼容层）。
 */
class JellyfinUiStateShapeTest {

    private val track = Track(
        id = "t1",
        title = "歌名",
        artist = "歌手",
        album = "专辑",
        durationMs = 1_000L,
        imageTag = "tag-t",
    )
    private val album = Album(id = "a1", name = "专辑", artist = "歌手", trackCount = 2, imageTag = "tag-a")
    private val playlist = Playlist(id = "p1", name = "列表", trackCount = 3, imageTag = "tag-p")
    private val searchResults = SearchResults(tracks = listOf(track), albums = listOf(album))
    private val cachedAlbum = CachedAlbum(album = album, tracks = listOf(track), totalCount = 2)
    private val recycleFile = LibraryRecycleFile(relPath = "歌手/专辑/歌名.flac", size = 42L, deletedAt = 1.0)

    // ── 默认值 ──────────────────────────────────────────────────────

    @Test
    fun `默认构造时每个旧读法都等于 slice 默认值`() {
        val state = JellyfinUiState()

        assertEquals(SessionState(), state.session)
        assertEquals(LibraryState(), state.library)
        assertEquals(DownloadsState(), state.downloads)

        // 会话
        assertEquals("", state.serverUrl)
        assertEquals(emptyList<String>(), state.serverUrlHistory)
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals(false, state.isLoading)
        assertEquals(false, state.isRestoring)
        assertNull(state.errorMessage)
        // 曲库
        assertNull(state.tracks)
        assertNull(state.discoverAlbums)
        assertNull(state.recentlyPlayed)
        assertNull(state.mostPlayed)
        assertNull(state.recentlyAdded)
        assertEquals(emptySet<LibrarySectionKey>(), state.discoverFailedSections)
        assertEquals(0, state.historyEpoch)
        assertEquals(false, state.isDiscoverRefreshing)
        assertNull(state.albums)
        assertNull(state.albumTracks)
        assertNull(state.selectedAlbum)
        assertNull(state.playlists)
        assertNull(state.playlistTracks)
        assertNull(state.selectedPlaylist)
        assertEquals(emptySet<String>(), state.multiSelectIds)
        assertEquals(false, state.isOffline)
        // 搜索
        assertEquals("", state.searchQuery)
        assertNull(state.searchResults)
        assertEquals(false, state.isSearching)
        assertEquals(false, state.isOfflineSearch)
        // 文件操作
        assertEquals(false, state.isFileOpsBusy)
        assertNull(state.fileOpsMessage)
        assertEquals(emptyList<LibraryRecycleFile>(), state.recycleFiles)
        assertEquals(false, state.isRecycleLoading)
        assertNull(state.recycleMessage)
        // 下载
        assertNull(state.cachedTrackIds)
        assertNull(state.cachedAlbums)
        assertEquals(false, state.isDeletingDownload)
        assertNull(state.downloadDeleteMessage)
    }

    // ── 旧写法 → slice ──────────────────────────────────────────────

    @Test
    fun `旧写法写会话字段落到 session slice`() {
        val state = JellyfinUiState().copy(
            serverUrl = "https://jelly.example",
            serverUrlHistory = listOf("https://jelly.example"),
            username = "alice",
            password = "secret",
            isLoading = true,
            isRestoring = true,
            errorMessage = "登录失败",
        )

        assertEquals("https://jelly.example", state.serverUrl)
        assertEquals("https://jelly.example", state.session.serverUrl)
        assertEquals(listOf("https://jelly.example"), state.serverUrlHistory)
        assertEquals(listOf("https://jelly.example"), state.session.serverUrlHistory)
        assertEquals("alice", state.username)
        assertEquals("alice", state.session.username)
        assertEquals("secret", state.password)
        assertEquals("secret", state.session.password)
        assertEquals(true, state.isLoading)
        assertEquals(true, state.session.isLoading)
        assertEquals(true, state.isRestoring)
        assertEquals(true, state.session.isRestoring)
        assertEquals("登录失败", state.errorMessage)
        assertEquals("登录失败", state.session.errorMessage)
    }

    @Test
    fun `旧写法写曲库字段落到 library slice`() {
        val state = JellyfinUiState().copy(
            tracks = listOf(track),
            discoverAlbums = listOf(album),
            recentlyPlayed = listOf(track),
            mostPlayed = listOf(track),
            recentlyAdded = listOf(album),
            discoverFailedSections = setOf(LibrarySectionKey.MostPlayed),
            historyEpoch = 3,
            isDiscoverRefreshing = true,
            albums = listOf(album),
            albumTracks = listOf(track),
            selectedAlbum = album,
            playlists = listOf(playlist),
            playlistTracks = listOf(track),
            selectedPlaylist = playlist,
            multiSelectIds = setOf("t1"),
            isOffline = true,
        )

        assertEquals(listOf(track), state.library.tracks)
        assertEquals(listOf(album), state.library.discoverAlbums)
        assertEquals(listOf(track), state.library.recentlyPlayed)
        assertEquals(listOf(track), state.library.mostPlayed)
        assertEquals(listOf(album), state.library.recentlyAdded)
        assertEquals(setOf(LibrarySectionKey.MostPlayed), state.library.discoverFailedSections)
        assertEquals(3, state.library.historyEpoch)
        assertEquals(true, state.library.isDiscoverRefreshing)
        assertEquals(listOf(album), state.library.albums)
        assertEquals(listOf(track), state.library.albumTracks)
        assertEquals(album, state.library.selectedAlbum)
        assertEquals(listOf(playlist), state.library.playlists)
        assertEquals(listOf(track), state.library.playlistTracks)
        assertEquals(playlist, state.library.selectedPlaylist)
        assertEquals(setOf("t1"), state.library.multiSelectIds)
        assertEquals(true, state.library.isOffline)

        // 旧读法（同一份数据，不是第二份）
        assertEquals(listOf(track), state.tracks)
        assertEquals(listOf(album), state.discoverAlbums)
        assertEquals(listOf(track), state.recentlyPlayed)
        assertEquals(listOf(track), state.mostPlayed)
        assertEquals(listOf(album), state.recentlyAdded)
        assertEquals(setOf(LibrarySectionKey.MostPlayed), state.discoverFailedSections)
        assertEquals(3, state.historyEpoch)
        assertEquals(true, state.isDiscoverRefreshing)
        assertEquals(listOf(album), state.albums)
        assertEquals(listOf(track), state.albumTracks)
        assertEquals(album, state.selectedAlbum)
        assertEquals(listOf(playlist), state.playlists)
        assertEquals(listOf(track), state.playlistTracks)
        assertEquals(playlist, state.selectedPlaylist)
        assertEquals(setOf("t1"), state.multiSelectIds)
        assertEquals(true, state.isOffline)
    }

    @Test
    fun `旧写法写搜索字段落到 search slice`() {
        val state = JellyfinUiState().copy(
            searchQuery = "周杰伦",
            searchResults = searchResults,
            isSearching = true,
            isOfflineSearch = true,
        )

        assertEquals("周杰伦", state.library.search.query)
        assertEquals(searchResults, state.library.search.results)
        assertEquals(true, state.library.search.isSearching)
        assertEquals(true, state.library.search.isOfflineSearch)

        assertEquals("周杰伦", state.searchQuery)
        assertEquals(searchResults, state.searchResults)
        assertEquals(true, state.isSearching)
        assertEquals(true, state.isOfflineSearch)
    }

    @Test
    fun `旧写法写文件操作字段落到 fileOps slice`() {
        val state = JellyfinUiState().copy(
            isFileOpsBusy = true,
            fileOpsMessage = "删除失败",
            recycleFiles = listOf(recycleFile),
            isRecycleLoading = true,
            recycleMessage = "已清空",
        )

        assertEquals(true, state.library.fileOps.isBusy)
        assertEquals("删除失败", state.library.fileOps.message)
        assertEquals(listOf(recycleFile), state.library.fileOps.recycleFiles)
        assertEquals(true, state.library.fileOps.isRecycleLoading)
        assertEquals("已清空", state.library.fileOps.recycleMessage)

        assertEquals(true, state.isFileOpsBusy)
        assertEquals("删除失败", state.fileOpsMessage)
        assertEquals(listOf(recycleFile), state.recycleFiles)
        assertEquals(true, state.isRecycleLoading)
        assertEquals("已清空", state.recycleMessage)
    }

    @Test
    fun `旧写法写下载字段落到 downloads slice`() {
        val state = JellyfinUiState().copy(
            cachedTrackIds = setOf("t1"),
            cachedAlbums = listOf(cachedAlbum),
            isDeletingDownload = true,
            downloadDeleteMessage = "已删除 1 首",
        )

        assertEquals(setOf("t1"), state.downloads.visibleTrackIds)
        assertEquals(listOf(cachedAlbum), state.downloads.cachedAlbums)
        assertEquals(true, state.downloads.isDeleting)
        assertEquals("已删除 1 首", state.downloads.deleteMessage)

        assertEquals(setOf("t1"), state.cachedTrackIds)
        assertEquals(listOf(cachedAlbum), state.cachedAlbums)
        assertEquals(true, state.isDeletingDownload)
        assertEquals("已删除 1 首", state.downloadDeleteMessage)
    }

    // ── slice 写法 → 旧读法 ─────────────────────────────────────────

    @Test
    fun `直接写 slice 后旧读法同步`() {
        val state = JellyfinUiState(
            session = SessionState(serverUrl = "https://a", username = "bob", isLoading = true),
            library = LibraryState(
                tracks = listOf(track),
                albums = listOf(album),
                isOffline = true,
                search = SearchState(query = "q", results = searchResults, isSearching = true),
                fileOps = FileOpsState(isBusy = true, message = "m", recycleMessage = "r"),
            ),
            downloads = DownloadsState(
                visibleTrackIds = setOf("t1"),
                cachedAlbums = listOf(cachedAlbum),
                isDeleting = true,
                deleteMessage = "d",
            ),
        )

        assertEquals("https://a", state.serverUrl)
        assertEquals("bob", state.username)
        assertEquals(true, state.isLoading)
        assertEquals(listOf(track), state.tracks)
        assertEquals(listOf(album), state.albums)
        assertEquals(true, state.isOffline)
        assertEquals("q", state.searchQuery)
        assertEquals(searchResults, state.searchResults)
        assertEquals(true, state.isSearching)
        assertEquals(true, state.isFileOpsBusy)
        assertEquals("m", state.fileOpsMessage)
        assertEquals("r", state.recycleMessage)
        assertEquals(setOf("t1"), state.cachedTrackIds)
        assertEquals(listOf(cachedAlbum), state.cachedAlbums)
        assertEquals(true, state.isDeletingDownload)
        assertEquals("d", state.downloadDeleteMessage)
    }

    @Test
    fun `用新 copy 只改一个 slice 时其它 slice 原样保留`() {
        val before = JellyfinUiState().copy(serverUrl = "https://a", tracks = listOf(track))
        val after = before.copy(library = before.library.copy(isOffline = true))

        assertEquals("https://a", after.serverUrl)
        assertSame(before.session, after.session)
        assertSame(before.downloads, after.downloads)
        assertEquals(listOf(track), after.tracks)
        assertEquals(true, after.isOffline)
    }

    @Test
    fun `旧写法只改一个字段时其它字段保持原值`() {
        val before = JellyfinUiState().copy(
            serverUrl = "https://a",
            username = "alice",
            tracks = listOf(track),
            searchQuery = "q",
            cachedTrackIds = setOf("t1"),
        )
        val after = before.copy(isOffline = true)

        assertEquals("https://a", after.serverUrl)
        assertEquals("alice", after.username)
        assertEquals(listOf(track), after.tracks)
        assertEquals("q", after.searchQuery)
        assertEquals(setOf("t1"), after.cachedTrackIds)
        assertEquals(true, after.isOffline)
        // 兼容 copy 会重建三个 slice（值不变），所以只断言值相等，不断言同一实例。
        assertEquals(before.session, after.session)
        assertEquals(before.downloads, after.downloads)
    }
}
