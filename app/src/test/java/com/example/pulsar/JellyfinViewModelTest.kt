package com.example.pulsar

import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinRequest
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
        private val searchResults: SearchResults = SearchResults()
    ) : JellyfinApi {
        var musicItemsCalls = 0
        var searchError: Throwable? = null
        val searchQueries = mutableListOf<String>()
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
        override fun streamUrl(mediaId: String, accessToken: String) = "stream"
        override fun imageRequest(itemId: String, imageTag: String?, accessToken: String) = JellyfinRequest("image", emptyMap())
        override fun authorizationHeader(accessToken: String) = "auth-header"
    }
}
