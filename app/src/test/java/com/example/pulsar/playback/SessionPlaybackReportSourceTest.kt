package com.example.pulsar.playback

import com.example.pulsar.SavedSession
import com.example.pulsar.SessionRepository
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.JellyfinSession
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.session.SessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SessionPlaybackReportSource` 是上报与 `SessionManager` 之间的生产适配（#22）：
 * 会话绑定、未登录跳过、以及「不每次重建数据源」都在这里钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionPlaybackReportSourceTest {

    private val saved = SavedSession(
        serverUrl = "https://media.example.com",
        userId = "user-1",
        username = "alice",
        accessToken = "token-1"
    )

    @Test
    fun `no channel before a session is published`() = runTest {
        val source = SessionPlaybackReportSource(managerFor(FakeSessionRepository(null)))

        assertNull(source.currentChannel())
    }

    @Test
    fun `restored session reports through the API created at restore time`() = runTest {
        val api = RecordingApi()
        val factory = CountingFactory(api)
        val manager = managerFor(FakeSessionRepository(saved), factory)
        manager.restore()
        val source = SessionPlaybackReportSource(manager)

        val channel = source.currentChannel()
        assertNotNull(channel)
        channel!!.reportPlaybackStarted("track-1", 10_000)
        // 再取一次通道（相当于切歌后的下一次上报）：仍然复用同一个 API，不新建数据源。
        source.currentChannel()!!.reportPlaybackStopped("track-1", 20_000)

        assertEquals(
            listOf(
                "start token-1 track-1 10000",
                "stop token-1 track-1 20000"
            ),
            api.calls
        )
        assertEquals(1, factory.requestedServerUrls.size)
    }

    @Test
    fun `signOut hides the channel immediately`() = runTest {
        val manager = managerFor(FakeSessionRepository(saved))
        manager.restore()
        val source = SessionPlaybackReportSource(manager)
        assertNotNull(source.currentChannel())

        manager.signOut()

        assertNull(source.currentChannel())
    }

    @Test
    fun `switching sessions binds reports to the new token`() = runTest {
        val api = RecordingApi()
        val manager = managerFor(FakeSessionRepository(null), CountingFactory(api))

        manager.login("https://media.example.com", "alice", "pw").getOrThrow()
        SessionPlaybackReportSource(manager).currentChannel()!!.reportPlaybackStarted("track-1", 1_000)

        api.loginResult = Result.success(JellyfinSession("user-2", "token-2"))
        manager.login("https://media.example.com", "bob", "pw").getOrThrow()
        SessionPlaybackReportSource(manager).currentChannel()!!.reportPlaybackStarted("track-2", 2_000)

        assertEquals(
            listOf(
                "start token-1 track-1 1000",
                "start token-2 track-2 2000"
            ),
            api.calls
        )
    }

    private fun TestScope.managerFor(
        store: FakeSessionRepository,
        factory: JellyfinApiFactory = CountingFactory(RecordingApi())
    ): SessionManager = SessionManager(store, factory, this)

    private class FakeSessionRepository(var session: SavedSession?) : SessionRepository {
        override suspend fun load(): SavedSession? = session
        override suspend fun save(session: SavedSession) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }

    private class CountingFactory(private val api: JellyfinApi) : JellyfinApiFactory {
        val requestedServerUrls = mutableListOf<String>()
        override suspend fun create(serverUrl: String): JellyfinApi {
            requestedServerUrls += serverUrl
            return api
        }
    }

    /** 只实现上报与登录；其余方法不该被这个适配层碰到。 */
    private class RecordingApi : JellyfinApi {
        val calls = mutableListOf<String>()
        var loginResult: Result<JellyfinSession> = Result.success(JellyfinSession("user-1", "token-1"))

        override suspend fun login(username: String, password: String): Result<JellyfinSession> = loginResult

        override fun authorizationHeader(accessToken: String): String = "MediaBrowser Token=\"$accessToken\""

        override suspend fun reportPlaybackStarted(
            accessToken: String,
            itemId: String,
            positionTicks: Long
        ): Result<Unit> {
            calls += "start $accessToken $itemId $positionTicks"
            return Result.success(Unit)
        }

        override suspend fun reportPlaybackStopped(
            accessToken: String,
            itemId: String,
            positionTicks: Long
        ): Result<Unit> {
            calls += "stop $accessToken $itemId $positionTicks"
            return Result.success(Unit)
        }

        override suspend fun getMusicItems(userId: String, accessToken: String) = unsupported<List<Track>>()
        override suspend fun getAlbums(userId: String, accessToken: String) = unsupported<List<Album>>()
        override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int) = unsupported<List<Album>>()
        override suspend fun getRecentlyPlayed(userId: String, accessToken: String, limit: Int) = unsupported<List<Track>>()
        override suspend fun getMostPlayed(userId: String, accessToken: String, limit: Int) = unsupported<List<Track>>()
        override suspend fun getRecentlyAdded(userId: String, accessToken: String, limit: Int) = unsupported<List<Album>>()
        override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String) = unsupported<List<Track>>()
        override suspend fun getPlaylists(userId: String, accessToken: String) = unsupported<List<Playlist>>()
        override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String) = unsupported<List<Track>>()
        override suspend fun search(userId: String, accessToken: String, query: String) = unsupported<SearchResults>()
        override suspend fun getLyrics(accessToken: String, trackId: String) = unsupported<Lyrics>()
        override fun streamUrl(mediaId: String, accessToken: String): String = unsupportedValue()
        override fun imageRequest(itemId: String, imageTag: String?, accessToken: String): JellyfinRequest = unsupportedValue()

        private fun <T> unsupported(): Result<T> =
            Result.failure(UnsupportedOperationException("上报适配层不应调用这个接口"))

        private fun <T> unsupportedValue(): T =
            throw UnsupportedOperationException("上报适配层不应调用这个接口")
    }
}
