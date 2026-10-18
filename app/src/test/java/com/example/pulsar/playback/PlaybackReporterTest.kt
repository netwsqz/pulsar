package com.example.pulsar.playback

import com.example.pulsar.SavedSession
import com.example.pulsar.SessionRepository
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.JellyfinSession
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    @Test
    fun `start reports item id and position ticks`() = runTest {
        val (reporter, api) = createReporter()

        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        assertEquals(listOf(Report("/Sessions/Playing", "track-1", 0L)), api.reports)
    }

    @Test
    fun `same track does not report start twice`() = runTest {
        val (reporter, api) = createReporter()

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        assertEquals(1, api.reports.size)
    }

    @Test
    fun `track change reports stop for previous track then start for next`() = runTest {
        val (reporter, api) = createReporter()

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 90_000)
        reporter.onTrackStarted("track-2", positionMs = 0)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Report("/Sessions/Playing", "track-1", 0L),
                // 停止上报带实际播放位置：90 s = 900,000,000 ticks
                Report("/Sessions/Playing/Stopped", "track-1", 900_000_000L),
                // 切歌后新的开始上报位置归零（旧曲的位置不能带过去）
                Report("/Sessions/Playing", "track-2", 0L),
            ),
            api.reports
        )
    }

    @Test
    fun `stop without a reported start sends nothing`() = runTest {
        val (reporter, api) = createReporter()

        reporter.onPlaybackStopped(positionMs = 5_000)
        advanceUntilIdle()

        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `local audio items are never reported`() = runTest {
        val (reporter, api) = createReporter()

        reporter.onTrackStarted("content://com.android.providers.media.documents/document/42", positionMs = 0)
        reporter.onTrackStarted("file:///storage/emulated/0/a.flac", positionMs = 0)
        advanceUntilIdle()

        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `stop is sent before the next start`() = runTest {
        val (reporter, api) = createReporter()
        api.gate = kotlinx.coroutines.CompletableDeferred()

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 1_000)
        reporter.onTrackStarted("track-2", positionMs = 0)
        api.gate?.complete(Unit)
        advanceUntilIdle()

        // 串行执行：切歌时「停上一首」一定排在「开始下一首」之前
        assertEquals(
            listOf(
                Report("/Sessions/Playing", "track-1", 0L),
                Report("/Sessions/Playing/Stopped", "track-1", 10_000_000L),
                Report("/Sessions/Playing", "track-2", 0L),
            ),
            api.reports
        )
    }

    @Test
    fun `track played while logged out is not stopped after login`() = runTest {
        val sessions = FakeSessions(null)
        val (reporter, api) = createReporter(sessions)

        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()
        // 登录后再切歌：不能补发一个没有对应开始的停止请求
        sessions.session = SavedSession("https://media.example.com", "user-1", "alice", "token-1")
        reporter.onPlaybackStopped(positionMs = 1_000)
        advanceUntilIdle()

        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `missing session skips reporting silently`() = runTest {
        val (reporter, api) = createReporter(sessions = FakeSessions(null))

        reporter.onTrackStarted("track-1", positionMs = 0)
        reporter.onPlaybackStopped(positionMs = 1_000)
        advanceUntilIdle()

        assertTrue(api.reports.isEmpty())
    }

    @Test
    fun `network failure is swallowed and does not break later reports`() = runTest {
        val (reporter, api) = createReporter()
        api.failing = true

        reporter.onTrackStarted("track-1", positionMs = 0)
        advanceUntilIdle()

        api.failing = false
        reporter.onPlaybackStopped(positionMs = 1_000)
        advanceUntilIdle()

        // 开始请求失败了，但停止请求照常发出（调用方一次异常都没收到）
        assertEquals(
            listOf(Report("/Sessions/Playing/Stopped", "track-1", 10_000_000L)),
            api.reports
        )
    }

    @Test
    fun `position ticks use ten thousand ticks per millisecond`() {
        assertEquals(0L, PlaybackReporter.positionTicks(0))
        assertEquals(10_000L, PlaybackReporter.positionTicks(1))
        assertEquals(2_145_000_000L, PlaybackReporter.positionTicks(214_500))
        // 负位置（播放器异常态）按 0 处理，别给服务端发负 ticks
        assertEquals(0L, PlaybackReporter.positionTicks(-5))
    }

    private data class Report(val path: String, val itemId: String, val positionTicks: Long)

    private class FakeSessions(var session: SavedSession?) : SessionRepository {
        override suspend fun load(): SavedSession? = session
        override suspend fun save(session: SavedSession) = Unit
        override suspend fun clear() = Unit
    }

    /** 只实现上报两条路径；其余方法测试用不到，统一返回空成功。 */
    private class RecordingApi : JellyfinApi {
        val reports = mutableListOf<Report>()
        var failing = false
        /** 挂住第一次上报，用来验证上报是串行排队的。 */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun reportPlaybackStarted(accessToken: String, itemId: String, positionTicks: Long) =
            record("/Sessions/Playing", itemId, positionTicks)

        override suspend fun reportPlaybackStopped(accessToken: String, itemId: String, positionTicks: Long) =
            record("/Sessions/Playing/Stopped", itemId, positionTicks)

        private suspend fun record(path: String, itemId: String, positionTicks: Long): Result<Unit> {
            gate?.await()
            gate = null
            if (failing) return Result.failure(JellyfinError.Network(java.io.IOException("offline")))
            reports += Report(path, itemId, positionTicks)
            return Result.success(Unit)
        }

        override suspend fun login(username: String, password: String) = Result.success(JellyfinSession("user-1", "token-1"))
        override suspend fun getMusicItems(userId: String, accessToken: String) = Result.success(emptyList<Track>())
        override suspend fun getAlbums(userId: String, accessToken: String) = Result.success(emptyList<Album>())
        override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int) = Result.success(emptyList<Album>())
        override suspend fun getRecentlyPlayed(userId: String, accessToken: String, limit: Int) = Result.success(emptyList<Track>())
        override suspend fun getMostPlayed(userId: String, accessToken: String, limit: Int) = Result.success(emptyList<Track>())
        override suspend fun getRecentlyAdded(userId: String, accessToken: String, limit: Int) = Result.success(emptyList<Album>())
        override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String) = Result.success(emptyList<Track>())
        override suspend fun getPlaylists(userId: String, accessToken: String) = Result.success(emptyList<Playlist>())
        override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String) = Result.success(emptyList<Track>())
        override suspend fun search(userId: String, accessToken: String, query: String) = Result.success(SearchResults())
        override suspend fun getLyrics(accessToken: String, trackId: String) = Result.success(Lyrics.NONE)
        override fun streamUrl(mediaId: String, accessToken: String) = "stream/$mediaId"
        override fun imageRequest(itemId: String, imageTag: String?, accessToken: String) = JellyfinRequest("image", emptyMap())
        override fun authorizationHeader(accessToken: String) = "auth-header"
    }

    private fun TestScope.createReporter(
        sessions: FakeSessions = FakeSessions(
            SavedSession(
                serverUrl = "https://media.example.com",
                userId = "user-1",
                username = "alice",
                accessToken = "token-1"
            )
        )
    ): Pair<PlaybackReporter, RecordingApi> {
        val api = RecordingApi()
        val factory = object : JellyfinApiFactory {
            override suspend fun create(serverUrl: String): JellyfinApi = api
        }
        return PlaybackReporter(sessions, factory, this) to api
    }
}
