package com.example.pulsar.session

import com.example.pulsar.SessionRepository
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinLoginApi
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.JellyfinUrlBuilder
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.data.jellyfin.SessionScopedJellyfinApi
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val saved = Session(
        serverUrl = "https://media.example.com",
        userId = "user-1",
        username = "alice",
        accessToken = "token-1"
    )

    @Test
    fun `restore publishes the saved session and its authorization header`() = runTest {
        val store = FakeSessionRepository(saved)
        val api = FakeJellyfinApi()
        val factory = FakeJellyfinApiFactory(api)
        val manager = SessionManager(store, factory, this)

        assertTrue(manager.restore())

        assertEquals(
            Session("https://media.example.com", "user-1", "alice", "token-1"),
            manager.current.value
        )
        assertSame(api, manager.api.value)
        // 内部读方取的是原子快照：会话与 API 必须同属一次发布。
        assertSame(api, manager.currentConnection()?.api)
        assertEquals(manager.current.value, manager.currentConnection()?.session)
        assertEquals("MediaBrowser Token=\"token-1\"", manager.authorizationHeader)
        assertEquals(listOf(saved), factory.requestedSessions)
        // 恢复会话时只把已保存的会话交给工厂，令牌不经过 URL / 日志。
        assertFalse(factory.requestedSessions.single().toString().contains("token-1"))
    }

    @Test
    fun `restore returns false when no session was saved`() = runTest {
        val store = FakeSessionRepository(null)
        val factory = FakeJellyfinApiFactory()
        val manager = SessionManager(store, factory, this)

        assertFalse(manager.restore())

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
        assertTrue("没有会话时不该创建 API", factory.requestedSessions.isEmpty())
    }

    @Test
    fun `restore returns false when the saved session cannot be read`() = runTest {
        val store = FakeSessionRepository(saved, loadError = IOException("disk unavailable"))
        val factory = FakeJellyfinApiFactory()
        val manager = SessionManager(store, factory, this)

        assertFalse(manager.restore())

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
        assertTrue("读失败时不该继续创建 API", factory.requestedSessions.isEmpty())
    }

    @Test
    fun `login saves the session and publishes it as current`() = runTest {
        val store = FakeSessionRepository(null)
        val api = FakeJellyfinApi(loginResult = Result.success(Session("https://media.example.com", "user-9", "bob", "token-9")))
        val factory = FakeJellyfinApiFactory(api)
        val manager = SessionManager(store, factory, this)

        val result = manager.login("https://media.example.com", "bob", "s3cret")

        val session = result.getOrThrow()
        assertEquals(
            Session("https://media.example.com", "user-9", "bob", "token-9"),
            session
        )
        // 登录先建无会话的登录接口，拿到会话后再建会话作用域 API；凭据不经过方法参数。
        assertEquals(listOf("https://media.example.com"), factory.requestedServerUrls)
        assertEquals(listOf(session), factory.requestedSessions)
        assertEquals(session, manager.current.value)
        assertSame(api, manager.api.value)
        assertEquals(
            Session("https://media.example.com", "user-9", "bob", "token-9"),
            store.session
        )
        assertEquals("MediaBrowser Token=\"token-9\"", manager.authorizationHeader)
    }

    @Test
    fun `login failure leaves no current session and does not touch the store`() = runTest {
        val store = FakeSessionRepository(null)
        val api = FakeJellyfinApi(loginResult = Result.failure(JellyfinError.InvalidCredentials()))
        val manager = SessionManager(store, FakeJellyfinApiFactory(api), this)

        assertTrue(manager.login("https://media.example.com", "bob", "wrong").isFailure)

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
        assertNull(store.session)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `signOut clears only the session store`() = runTest {
        val store = FakeSessionRepository(saved)
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)
        manager.restore()

        manager.signOut()

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.currentConnection())
        assertNull(manager.authorizationHeader)
        assertNull(store.session)
        assertEquals(1, store.clearCalls)
        // ADR-0007：下载字节与下载名单不在 SessionManager 的依赖里，登出只清会话。
    }

    @Test
    fun `expire clears the session and store but keeps the login hint`() = runTest {
        val store = FakeSessionRepository(saved)
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)
        manager.restore()

        manager.expire()
        advanceUntilIdle()

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
        assertNull(store.session)
        assertEquals(
            LoginHint("https://media.example.com", "alice"),
            manager.loginHint.value
        )
    }

    @Test
    fun `repeated expire keeps the login hint`() = runTest {
        val store = FakeSessionRepository(saved)
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)
        manager.restore()

        manager.expire()
        advanceUntilIdle()
        // 多个在途请求可能同时报 401，第二次 expire 不能把预填信息冲掉。
        manager.expire()
        advanceUntilIdle()

        assertEquals(
            LoginHint("https://media.example.com", "alice"),
            manager.loginHint.value
        )
    }

    @Test
    fun `login returns failure when the session cannot be saved`() = runTest {
        val store = FakeSessionRepository(null).apply { saveError = IOException("disk full") }
        val api = FakeJellyfinApi(loginResult = Result.success(Session("https://media.example.com", "user-9", "bob", "token-9")))
        val manager = SessionManager(store, FakeJellyfinApiFactory(api), this)

        assertTrue(manager.login("https://media.example.com", "bob", "pw").isFailure)

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
    }

    @Test
    fun `signOut clears the in-memory session even when the store fails`() = runTest {
        val store = FakeSessionRepository(saved).apply { clearError = IOException("disk unavailable") }
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)
        manager.restore()

        val error = runCatching { manager.signOut() }.exceptionOrNull()

        assertTrue(error is IOException)
        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
    }

    @Test
    fun `failed login clears the previous session`() = runTest {
        val store = FakeSessionRepository(saved)
        val api = FakeJellyfinApi(loginResult = Result.failure(JellyfinError.InvalidCredentials()))
        val manager = SessionManager(store, FakeJellyfinApiFactory(api), this)
        manager.restore()

        assertTrue(manager.login("https://media.example.com", "bob", "wrong").isFailure)

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
        // 与现有 ViewModel 一致：登录失败不回滚已保存的会话，下次启动仍可恢复。
        assertEquals(saved, store.session)
    }

    @Test
    fun `expire does not wipe a login that is still being saved`() = runTest {
        val store = GatedSaveRepository(null)
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)

        val login = launch { manager.login("https://media.example.com", "bob", "pw") }
        store.saveStarted.await()

        manager.expire()
        store.releaseSave.complete(Unit)
        login.join()
        advanceUntilIdle()

        assertEquals(
            Session("https://media.example.com", "user-1", "bob", "token-1"),
            store.session
        )
        assertNotNull(manager.current.value)
    }

    @Test
    fun `signOut clears the login hint left by expiry`() = runTest {
        val store = FakeSessionRepository(saved)
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)
        manager.restore()
        manager.expire()
        advanceUntilIdle()

        manager.signOut()

        assertNull(manager.loginHint.value)
    }

    @Test
    fun `authorizationHeader follows the current session`() = runTest {
        val store = FakeSessionRepository(null)
        val api = FakeJellyfinApi()
        val manager = SessionManager(store, FakeJellyfinApiFactory(api), this)

        manager.login("https://media.example.com", "alice", "pw").getOrThrow()
        assertEquals("MediaBrowser Token=\"token-1\"", manager.authorizationHeader)

        api.loginResult = Result.success(Session("https://media.example.com", "user-2", "bob", "token-2"))
        manager.login("https://media.example.com", "bob", "pw").getOrThrow()
        assertEquals("MediaBrowser Token=\"token-2\"", manager.authorizationHeader)

        manager.signOut()
        assertNull(manager.authorizationHeader)
    }

    @Test
    fun `clearInMemory drops the session but keeps it for the next launch`() = runTest {
        val store = FakeSessionRepository(saved)
        val manager = SessionManager(store, FakeJellyfinApiFactory(), this)
        manager.restore()

        manager.clearInMemory()

        assertNull(manager.current.value)
        assertNull(manager.api.value)
        assertNull(manager.authorizationHeader)
        assertEquals(saved, store.session)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun `session token is only sent in the authorization header`() {
        val session = Session("https://media.example.com", "user-1", "alice", "secret-token")
        val builder = JellyfinUrlBuilder(session.serverUrl)

        val stream = builder.mediaStreamRequest("track-1", session.accessToken, "device-1")
        val image = builder.imageRequest("item-1", "tag-1", session.accessToken, "device-1")

        assertFalse(stream.url.contains(session.accessToken))
        assertFalse(image.url.contains(session.accessToken))
        assertTrue(stream.headers.getValue("Authorization").contains(session.accessToken))
        assertTrue(image.headers.getValue("Authorization").contains(session.accessToken))
    }

    @Test
    fun `session toString does not expose the access token`() {
        val session = Session("https://media.example.com", "user-1", "alice", "secret-token")

        assertFalse(session.toString().contains("secret-token"))
    }

    private class FakeSessionRepository(
        var session: Session?,
        var loadError: Throwable? = null
    ) : SessionRepository {
        var saveError: Throwable? = null
        var clearError: Throwable? = null
        var saveCalls = 0
        var clearCalls = 0

        override suspend fun load(): Session? {
            loadError?.let { throw it }
            return session
        }

        override suspend fun save(session: Session) {
            saveError?.let { throw it }
            saveCalls++
            this.session = session
        }

        override suspend fun clear() {
            clearError?.let { throw it }
            clearCalls++
            session = null
        }
    }

    /** save 写完但还没 publish 时挂住，用来复现 expire 与登录的竞态。 */
    private class GatedSaveRepository(var session: Session?) : SessionRepository {
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()

        override suspend fun load(): Session? = session

        override suspend fun save(session: Session) {
            this.session = session
            saveStarted.complete(Unit)
            releaseSave.await()
        }

        override suspend fun clear() {
            session = null
        }
    }

    private class FakeJellyfinApiFactory(
        private val api: FakeJellyfinApi = FakeJellyfinApi()
    ) : JellyfinApiFactory {
        val requestedServerUrls = mutableListOf<String>()
        val requestedSessions = mutableListOf<Session>()

        override suspend fun createLoginApi(serverUrl: String): JellyfinLoginApi {
            requestedServerUrls += serverUrl
            return api
        }

        override suspend fun create(session: Session): SessionScopedJellyfinApi {
            requestedSessions += session
            return api
        }
    }

    /** SessionManager 只用到 login 与 authorizationHeader，其余方法仅为满足新接口。 */
    private class FakeJellyfinApi(
        var loginResult: Result<Session> = Result.success(
            Session("https://media.example.com", "user-1", "alice", "token-1")
        )
    ) : SessionScopedJellyfinApi, JellyfinLoginApi {
        private var token = "token-1"

        override suspend fun login(username: String, password: String): Result<Session> =
            loginResult.map { it.copy(username = username) }.onSuccess { token = it.accessToken }

        override fun authorizationHeader(): String = "MediaBrowser Token=\"$token\""

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
        override suspend fun reportPlaybackStarted(itemId: String, positionTicks: Long) = unsupported<Unit>()
        override suspend fun reportPlaybackStopped(itemId: String, positionTicks: Long) = unsupported<Unit>()
        override fun imageRequest(itemId: String, imageTag: String?): JellyfinRequest =
            unsupportedValue()

        private fun <T> unsupported(): Result<T> =
            Result.failure(UnsupportedOperationException("SessionManagerTest 不应调用这个接口"))

        private fun <T> unsupportedValue(): T =
            throw UnsupportedOperationException("SessionManagerTest 不应调用这个接口")
    }
}
