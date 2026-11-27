package com.example.pulsar.playback

import com.example.pulsar.SessionRepository
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinLoginApi
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.data.jellyfin.SessionScopedJellyfinApi
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.session.Session
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

    private val saved = Session(
        serverUrl = "https://media.example.com",
        userId = "user-1",
        username = "alice",
        accessToken = "token-1"
    )

    @Test
    fun `no channel before a session is published`() = runTest {
        val manager = managerFor(FakeSessionRepository(null))
        val source = SessionPlaybackReportSource(manager)

        assertNull(source.channelFor(PlaybackReportIdentity("无会话", "track-1")))
    }

    @Test
    fun `restored session reports through the API created at restore time`() = runTest {
        val calls = mutableListOf<String>()
        val factory = CountingFactory(calls)
        val manager = managerFor(FakeSessionRepository(saved), factory)
        manager.restore()
        val source = SessionPlaybackReportSource(manager)
        val owner = checkNotNull(manager.currentConnection()).playbackReportOwner

        val channel = source.channelFor(PlaybackReportIdentity(owner, "track-1"))
        assertNotNull(channel)
        channel!!.reportPlaybackStarted("track-1", 10_000)
        // 再取一次通道（相当于切歌后的下一次上报）：仍然复用同一个 API，不新建数据源。
        source.channelFor(PlaybackReportIdentity(owner, "track-1"))!!.reportPlaybackStopped("track-1", 20_000)

        assertEquals(
            listOf(
                "start token-1 track-1 10000",
                "stop token-1 track-1 20000"
            ),
            calls
        )
        assertEquals(1, factory.requestedSessions.size)
    }

    @Test
    fun `signOut hides the channel immediately`() = runTest {
        val manager = managerFor(FakeSessionRepository(saved))
        manager.restore()
        val source = SessionPlaybackReportSource(manager)
        val identity = PlaybackReportIdentity(checkNotNull(manager.currentConnection()).playbackReportOwner, "track-1")
        assertNotNull(source.channelFor(identity))

        manager.signOut()

        assertNull(source.channelFor(identity))
    }

    @Test
    fun `switching sessions binds reports to the new token`() = runTest {
        val calls = mutableListOf<String>()
        val factory = CountingFactory(calls)
        val manager = managerFor(FakeSessionRepository(null), factory)

        manager.login("https://media.example.com", "alice", "pw").getOrThrow()
        val source = SessionPlaybackReportSource(manager)
        val oldOwner = manager.currentConnection()!!.playbackReportOwner
        source.channelFor(PlaybackReportIdentity(oldOwner, "track-1"))!!.reportPlaybackStarted("track-1", 1_000)

        factory.loginResult = Result.success(
            Session("https://media.example.com", "user-2", "bob", "token-2")
        )
        manager.login("https://media.example.com", "bob", "pw").getOrThrow()
        assertNull(source.channelFor(PlaybackReportIdentity(oldOwner, "track-1")))
        source.channelFor(PlaybackReportIdentity(checkNotNull(manager.currentConnection()).playbackReportOwner, "track-2"))!!
            .reportPlaybackStarted("track-2", 2_000)

        assertEquals(
            listOf(
                "start token-1 track-1 1000",
                "start token-2 track-2 2000"
            ),
            calls
        )
    }

    private fun TestScope.managerFor(
        store: FakeSessionRepository,
        factory: JellyfinApiFactory = CountingFactory()
    ): SessionManager = SessionManager(store, factory, this)

    private class FakeSessionRepository(var session: Session?) : SessionRepository {
        override suspend fun load(): Session? = session
        override suspend fun save(session: Session) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }

    /** 每次登录 / 恢复都按会话建一个会记录调用的 API；[requestedSessions] 用来数重建次数。 */
    private class CountingFactory(
        private val calls: MutableList<String> = mutableListOf()
    ) : JellyfinApiFactory {
        val requestedSessions = mutableListOf<Session>()
        var loginResult: Result<Session> = Result.success(
            Session("https://media.example.com", "user-1", "alice", "token-1")
        )

        override suspend fun createLoginApi(serverUrl: String): JellyfinLoginApi =
            object : JellyfinLoginApi {
                override suspend fun login(username: String, password: String): Result<Session> =
                    loginResult.map { it.copy(serverUrl = serverUrl, username = username) }
            }

        override suspend fun create(session: Session): SessionScopedJellyfinApi {
            requestedSessions += session
            return RecordingApi(session, calls)
        }
    }

    /** 只实现上报与鉴权头；其余方法不该被这个适配层碰到。 */
    private class RecordingApi(
        private val session: Session,
        private val calls: MutableList<String>,
    ) : SessionScopedJellyfinApi {

        override suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long): Result<Unit> {
            calls += "start ${session.accessToken} $itemId $positionTicks"
            return Result.success(Unit)
        }

        override suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long): Result<Unit> {
            calls += "stop ${session.accessToken} $itemId $positionTicks"
            return Result.success(Unit)
        }

        override fun authorizationHeader(): String = "MediaBrowser Token=\"${session.accessToken}\""

        override suspend fun getMusicItems() = unsupported<List<Track>>()
        override suspend fun getAlbums() = unsupported<List<Album>>()
        override suspend fun getRandomAlbums(limit: Int) = unsupported<List<Album>>()
        override suspend fun getRecentlyPlayed(limit: Int) = unsupported<List<Track>>()
        override suspend fun getMostPlayed(limit: Int) = unsupported<List<Track>>()
        override suspend fun getRecentlyAdded(limit: Int) = unsupported<List<Album>>()
        override suspend fun getAlbumTracks(albumId: String) = unsupported<List<Track>>()
        override suspend fun getPlaylists() = unsupported<List<Playlist>>()
        override suspend fun getPlaylistTracks(playlistId: String) = unsupported<List<Track>>()
        override suspend fun search(query: String) = unsupported<SearchResults>()
        override suspend fun getLyrics(trackId: String) = unsupported<Lyrics>()
        override fun streamUrl(mediaId: String): String = unsupportedValue()
        override fun imageRequest(itemId: String, imageTag: String?): JellyfinRequest = unsupportedValue()

        private fun <T> unsupported(): Result<T> =
            Result.failure(UnsupportedOperationException("上报适配层不应调用这个接口"))

        private fun <T> unsupportedValue(): T =
            throw UnsupportedOperationException("上报适配层不应调用这个接口")
    }
}
