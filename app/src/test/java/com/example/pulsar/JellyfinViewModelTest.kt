package com.example.pulsar

import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinViewModelTest {
    private val track = Track("track-1", "Song", "Artist", "Album", 1000L, null)

    @Test
    fun `restores saved session and loads tracks`() = runTest {
        val session = SavedSession("https://server.test", "user-1", "user", "secret")
        val sessions = FakeSessionRepository(session)
        val api = FakeJellyfinApi(tracks = listOf(track))
        val authorization = FakeAuthorizationSink()
        val viewModel = createViewModel(sessions, api, authorization)

        advanceUntilIdle()

        assertEquals(listOf(track), viewModel.state.value.tracks)
        assertEquals("auth-header", authorization.value)
    }

    @Test
    fun `does not request when there is no saved session`() = runTest {
        val api = FakeJellyfinApi()
        val viewModel = createViewModel(FakeSessionRepository(null), api)

        advanceUntilIdle()

        assertEquals(0, api.musicItemsCalls)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `启动恢复期间标记恢复中，界面进主页面骨架而非空登录表单`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val viewModel = createViewModel(sessions, FakeJellyfinApi(tracks = listOf(track)))

        // 还没推进调度器：恢复协程尚未跑完。此时 UI 必须知道"正在恢复"，才能
        // 直接进主页面并用灰色波浪骨架占位，而不是先闪一屏空白登录表单。
        assertTrue(viewModel.state.value.isRestoring)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `恢复成功清除恢复中标记并落到曲库`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val viewModel = createViewModel(sessions, FakeJellyfinApi(tracks = listOf(track)))

        advanceUntilIdle()

        assertTrue(!viewModel.state.value.isRestoring)
        assertEquals(listOf(track), viewModel.state.value.tracks)
    }

    @Test
    fun `无保存会话时恢复中标记清除，落到登录表单`() = runTest {
        val viewModel = createViewModel(FakeSessionRepository(null), FakeJellyfinApi())

        advanceUntilIdle()

        assertTrue(!viewModel.state.value.isRestoring)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `恢复失败也要清除恢复中标记，不得停在骨架屏`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(musicItemsError = JellyfinError.Network(IllegalStateException()))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()

        assertTrue(!viewModel.state.value.isRestoring)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `恢复失败时用已保存的地址与用户名预填登录表单`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(musicItemsError = JellyfinError.Network(IllegalStateException()))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()

        // 连不上服务器时回退到登录表单，但地址/用户名已填好，用户只需补密码重试。
        assertEquals("https://server.test", viewModel.state.value.serverUrl)
        assertEquals("user", viewModel.state.value.username)
    }

    @Test
    fun `unauthorized restore clears session and memory authorization`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(musicItemsError = JellyfinError.Unauthorized())
        val authorization = FakeAuthorizationSink("old-auth")
        val viewModel = createViewModel(sessions, api, authorization)

        advanceUntilIdle()

        assertTrue(sessions.cleared)
        assertNull(authorization.value)
        assertTrue(viewModel.state.value.errorMessage!!.contains("失效"))
    }

    @Test
    fun `unauthorized library request expires current session`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(albumsError = JellyfinError.Unauthorized())
        val authorization = FakeAuthorizationSink("active-auth")
        val viewModel = createViewModel(sessions, api, authorization)

        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        assertNull(authorization.value)
        assertTrue(sessions.cleared)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `network failure keeps saved session`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(musicItemsError = JellyfinError.Network(IllegalStateException()))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()

        assertTrue(!sessions.cleared)
        assertTrue(viewModel.state.value.errorMessage!!.contains("连接"))
    }

    @Test
    fun `expired session clears cached library and playback state`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(tracks = listOf(track))
        val authorization = FakeAuthorizationSink("active-auth")
        val viewModel = createViewModel(sessions, api, authorization)

        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(track), "track-1")
        assertTrue(viewModel.state.value.albums != null)
        assertTrue(viewModel.playbackTracks.value.isNotEmpty())

        api.searchError = JellyfinError.Unauthorized()
        viewModel.updateSearchQuery("query")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertNull(authorization.value)
        assertTrue(sessions.cleared)
        assertNull(viewModel.state.value.albums)
        assertNull(viewModel.state.value.tracks)
        assertTrue(viewModel.playbackTracks.value.isEmpty())
        assertNull(viewModel.currentTrack.value)
        assertEquals("登录已失效，请重新登录", viewModel.state.value.errorMessage)
    }

    @Test
    fun `debounced search requests only latest query`() = runTest {
        val api = FakeJellyfinApi(searchResults = SearchResults(tracks = listOf(track)))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.updateSearchQuery("old")
        advanceTimeBy(100)
        viewModel.updateSearchQuery("latest")
        advanceTimeBy(299)
        assertEquals(emptyList<String>(), api.searchQueries)
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(listOf("latest"), api.searchQueries)
        assertEquals(listOf(track), viewModel.state.value.searchResults?.tracks)
    }

    @Test
    fun `clearing search cancels request and clears results`() = runTest {
        val api = FakeJellyfinApi(searchResults = SearchResults(tracks = listOf(track)))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.updateSearchQuery("query")
        viewModel.updateSearchQuery("")
        advanceUntilIdle()

        assertTrue(api.searchQueries.isEmpty())
        assertNull(viewModel.state.value.searchResults)
        assertEquals(false, viewModel.state.value.isSearching)
    }

    // ===== 歌词加载（loadLyricsForCurrentTrack / clearLyrics） =====

    @Test
    fun `loadLyricsForCurrentTrack 请求当前曲目并更新歌词状态`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val synced = Lyrics(
            isSynced = true,
            lines = listOf(com.example.pulsar.data.jellyfin.LyricLine(0L, "第一行"))
        )
        val api = FakeJellyfinApi(lyricsResult = Result.success(synced))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        assertEquals(listOf("track-1"), api.lyricsTrackIds)
        assertEquals(synced, viewModel.lyrics.value)
    }

    @Test
    fun `切歌后再次加载歌词 最终状态只反映最后一次请求`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi()
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        val trackA = Track("track-a", "A", "Artist", "", 1000L, null)
        val trackB = Track("track-b", "B", "Artist", "", 1000L, null)
        viewModel.setPlaybackQueue(listOf(trackA, trackB), trackA.id)
        viewModel.loadLyricsForCurrentTrack()
        // 第一首响应还没落地就切到 B：A 的请求被取消（不重复发），B 正常发起。
        viewModel.setPlaybackQueue(listOf(trackA, trackB), trackB.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        // A 的请求在启动前就被取消，只有 B 真正发出。
        assertEquals(listOf("track-b"), api.lyricsTrackIds)
    }

    @Test
    fun `无会话时加载歌词不发起请求`() = runTest {
        val api = FakeJellyfinApi()
        val viewModel = createViewModel(FakeSessionRepository(null), api)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        assertEquals(0, api.lyricsCalls)
    }

    @Test
    fun `歌词请求失败静默回退到无歌词`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(lyricsResult = Result.failure(JellyfinError.Network(IOException())))
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        assertEquals(Lyrics.NONE, viewModel.lyrics.value)
    }

    @Test
    fun `clearLyrics 清空状态且后续旧响应不写入`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val synced = Lyrics(isSynced = false, lines = emptyList())
        val api = FakeJellyfinApi(lyricsResult = Result.success(synced))
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        // clear 发生在响应落地前（模拟登出），响应必须作废。
        viewModel.clearLyrics()
        advanceUntilIdle()

        assertEquals(Lyrics.NONE, viewModel.lyrics.value)
    }

    @Test
    fun `会话过期清空歌词`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val synced = Lyrics(isSynced = false, lines = emptyList())
        val api = FakeJellyfinApi(lyricsResult = Result.success(synced))
        val authorization = FakeAuthorizationSink("active-auth")
        val viewModel = createViewModel(sessions, api, authorization)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()
        assertEquals(synced, viewModel.lyrics.value)

        // 曲库请求撞上 Unauthorized → 走真实会话过期路径，歌词必须随之清空。
        api.albumsError = JellyfinError.Unauthorized()
        viewModel.loadAlbums()
        advanceUntilIdle()

        assertEquals(Lyrics.NONE, viewModel.lyrics.value)
        assertNull(authorization.value)
        assertTrue(sessions.cleared)
    }

    private fun TestScope.createViewModel(
        sessions: FakeSessionRepository,
        api: FakeJellyfinApi,
        authorization: FakeAuthorizationSink = FakeAuthorizationSink()
    ): JellyfinViewModel = JellyfinViewModel(
        sessions, FakeApiFactory(api), authorization, StandardTestDispatcher(testScheduler)
    )

    private class FakeSessionRepository(private var session: SavedSession?) : SessionRepository {
        var cleared = false
        override suspend fun load() = session
        override suspend fun save(session: SavedSession) { this.session = session }
        override suspend fun clear() { cleared = true; session = null }
    }

    private class FakeApiFactory(private val api: JellyfinApi) : JellyfinApiFactory {
        override suspend fun create(serverUrl: String): JellyfinApi = api
    }

    private class FakeAuthorizationSink(initial: String? = null) : AuthorizationSink {
        var value: String? = initial
        override var activeAuthorization: String?
            get() = value
            set(value) { this.value = value }
    }

    private class FakeJellyfinApi(
        private val tracks: List<Track> = emptyList(),
        var musicItemsError: Throwable? = null,
        var albumsError: Throwable? = null,
        private val searchResults: SearchResults = SearchResults(),
        var lyricsResult: Result<Lyrics>? = null
    ) : JellyfinApi {
        var musicItemsCalls = 0
        var searchError: Throwable? = null
        val searchQueries = mutableListOf<String>()
        var lyricsCalls = 0
        val lyricsTrackIds = mutableListOf<String>()
        override suspend fun login(username: String, password: String) = Result.failure<com.example.pulsar.data.jellyfin.JellyfinSession>(IllegalStateException())
        override suspend fun getMusicItems(userId: String, accessToken: String): Result<List<Track>> {
            musicItemsCalls++
            return musicItemsError?.let { Result.failure(it) } ?: Result.success(tracks)
        }
        override suspend fun getAlbums(userId: String, accessToken: String): Result<List<Album>> =
            albumsError?.let { Result.failure(it) } ?: Result.success(emptyList())
        override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int) = Result.success(emptyList<Album>())
        override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String) = Result.success(emptyList<Track>())
        override suspend fun getPlaylists(userId: String, accessToken: String) = Result.success(emptyList<Playlist>())
        override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String) = Result.success(emptyList<Track>())
        override suspend fun search(userId: String, accessToken: String, query: String): Result<SearchResults> {
            searchQueries += query
            return searchError?.let { Result.failure(it) } ?: Result.success(searchResults)
        }
        override suspend fun getLyrics(accessToken: String, trackId: String): Result<Lyrics> {
            lyricsCalls++
            lyricsTrackIds += trackId
            return lyricsResult ?: Result.success(Lyrics.NONE)
        }
        override fun streamUrl(mediaId: String, accessToken: String) = "stream"
        override fun imageRequest(itemId: String, imageTag: String?, accessToken: String) = JellyfinRequest("image", emptyMap())
        override fun authorizationHeader(accessToken: String) = "auth-header"
    }
}
