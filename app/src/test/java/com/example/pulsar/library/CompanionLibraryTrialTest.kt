package com.example.pulsar.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.CompanionPairingStore
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.QueueTrack
import com.example.pulsar.domain.model.Track
import com.example.pulsar.playback.FakePlaybackRemote
import com.example.pulsar.playback.FakeTrackMediaItemFactory
import com.example.pulsar.playback.PlaybackIntent
import com.example.pulsar.playback.PlaybackSession
import com.example.pulsar.playback.RemoteSnapshot
import com.example.pulsar.search.QqSearchInput
import com.example.pulsar.search.SearchInput
import com.example.pulsar.search.SearchViewState
import com.example.pulsar.search.unifiedSearchViewState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionLibraryTrialTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `沿用配对 从歌曲和合并搜索点同一首都是同一原文件 QQ 失败不遮结果`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val store = CompanionPairingStore(preferences)
        val listRequests = mutableListOf<String>()
        HttpClient(MockEngine { request ->
            listRequests += request.url.toString()
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            respond("""{"tracks":[{"id":"$id","title":"浮夸","artist":"陈奕迅","album":"U 87"}]}""")
        }).use { client ->
            CompanionLibraryConnection(client, store).pair("192.168.1.8", "test-key").getOrThrow()
            listRequests.clear()
            val trial = CompanionLibraryTrial(
                CompanionLibraryConnection(client, store), backgroundScope, StandardTestDispatcher(testScheduler),
            )
            trial.enter()
            val songs = checkNotNull(trial.state.first { it.tracks != null }.tracks)
            trial.updateSearchQuery("  u 87  ")
            assertNull(trial.state.value.search.results)
            advanceTimeBy(LIBRARY_SEARCH_DEBOUNCE_MS)
            runCurrent()
            val search = trial.state.value.search
            val combined = unifiedSearchViewState(
                SearchInput(query = search.query, results = search.results, isSearching = search.isSearching),
                QqSearchInput(searchStatus = "QQ 音乐凭证已过期"),
            ) as SearchViewState.Results
            assertEquals(songs, combined.library?.tracks)
            assertEquals("QQ 音乐凭证已过期", combined.qqStatus)

            val remote = FakePlaybackRemote().apply { snapshot = RemoteSnapshot(isConnected = true) }
            val requestedUrls = mutableListOf<String>()
            val mediaItems = FakeTrackMediaItemFactory { track ->
                trial.streamUrl(track)?.let { url ->
                    requestedUrls += url
                    androidx.media3.common.MediaItem.Builder().setMediaId(track.id).build()
                }
            }
            val playback = PlaybackSession(remote, mediaItems, trial, backgroundScope)
            for (tracks in listOf(songs, combined.library!!.tracks)) {
                assertTrue(playback.dispatch(PlaybackIntent.Play(tracks, tracks.single().id)))
            }
            assertTrue(playback.dispatch(PlaybackIntent.SeekTo(20_000L)))
            assertEquals(listOf(20_000L), remote.seekToCalls)
            val mediaId = songs.single().id
            assertTrue(mediaId.startsWith("pc:") && mediaId.endsWith(":$id"))
            assertEquals(listOf(mediaId, mediaId), remote.setQueueCalls.map { it.items.single().mediaId })
            assertEquals(List(2) { "http://192.168.1.8:8900/api/library/tracks/$id/audio" }, requestedUrls)
            assertEquals(listOf("http://192.168.1.8:8900/api/library/tracks"), listRequests)
            playback.close()
        }
    }

    @Test
    fun `已有配对时断连 扫描中 密钥失效和空库可区分 QQ 有结果也不遮住重试提示`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val store = CompanionPairingStore(preferences)
        var status: HttpStatusCode? = HttpStatusCode.OK
        HttpClient(MockEngine { request ->
            assertEquals("Bearer test-key", request.headers[HttpHeaders.Authorization])
            respond("""{"tracks":[]}""", status ?: throw IOException("电脑关机"))
        }).use { client ->
            CompanionLibraryConnection(client, store).pair("192.168.1.8", "test-key").getOrThrow()
            val trial = CompanionLibraryTrial(
                CompanionLibraryConnection(client, store), backgroundScope, StandardTestDispatcher(testScheduler),
            )
            status = null
            trial.enter()
            trial.updateSearchQuery("浮夸")
            val offline = trial.state.first { !it.isLoading }
            assertEquals(CompanionLibraryError.Network, offline.error)
            assertNull(offline.tracks)
            assertEquals(false, offline.needsPairing)
            assertEquals("http://192.168.1.8:8900", offline.serviceUrl)

            val qqSong = QQSongDto(title = "浮夸", singer = "陈奕迅", songMid = "qq-song")
            val combined = unifiedSearchViewState(
                SearchInput(offline.search.query, errorMessage = offline.error?.message, showLibraryErrorWithQqResults = true),
                QqSearchInput(songs = listOf(qqSong)),
            ) as SearchViewState.Results
            assertEquals(listOf(qqSong), combined.qqSongs)
            assertEquals(CompanionLibraryError.Network.message, combined.libraryError)

            status = HttpStatusCode.ServiceUnavailable
            trial.retry()
            val scanning = trial.state.first { !it.isLoading }
            assertEquals(CompanionLibraryError.LibraryNotReady, scanning.error)
            assertEquals(false, scanning.needsPairing)

            status = HttpStatusCode.Unauthorized
            trial.retry()
            val unauthorized = trial.state.first { !it.isLoading }
            assertEquals(CompanionLibraryError.Unauthorized, unauthorized.error)
            assertTrue(unauthorized.needsPairing)

            status = HttpStatusCode.OK
            trial.retry()
            val empty = trial.state.first { !it.isLoading }
            assertEquals(emptyList<Any>(), empty.tracks)
            assertNull(empty.error)
            assertEquals(false, empty.needsPairing)
            advanceTimeBy(LIBRARY_SEARCH_DEBOUNCE_MS)
            runCurrent()
            assertEquals(emptyList<Any>(), trial.state.value.search.results?.tracks)
        }
    }

    @Test
    fun `试用不把旧曲库歌曲或另一台电脑同路径的歌曲加入当前队列`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        HttpClient(MockEngine {
            respond("""{"tracks":[{"id":"$id","title":"浮夸","artist":"陈奕迅","album":"U 87"}]}""")
        }).use { client ->
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            connection.pair("192.168.1.8", "test-key").getOrThrow()
            val trial = CompanionLibraryTrial(connection, backgroundScope, StandardTestDispatcher(testScheduler))
            trial.enter()
            val oldPc = checkNotNull(trial.state.first { it.tracks != null }.tracks).single()
            trial.pair("192.168.1.9", "new-test-key").getOrThrow()
            val current = trial.state.value.tracks!!.single()
            assertNull(trial.streamUrl(oldPc))
            assertNull(trial.streamUrl(Track(id, current.title, current.artist, current.album, null, null)))
            assertEquals("http://192.168.1.9:8900/api/library/tracks/$id/audio", trial.streamUrl(current))

            val oldQueue = QueueTrack(id, "旧曲库的歌曲", "歌手", "专辑", null, true)
            val remote = FakePlaybackRemote().apply { snapshot = RemoteSnapshot(isConnected = true, queue = listOf(oldQueue)) }
            val playback = PlaybackSession(remote, FakeTrackMediaItemFactory(), trial, backgroundScope)
            playback.dispatch(PlaybackIntent.AddToQueue(current))
            assertTrue(remote.insertedItems.isEmpty())
            assertTrue(remote.singleItems.isEmpty())
            assertTrue(remote.setQueueCalls.isEmpty())

            assertTrue(playback.dispatch(PlaybackIntent.Play(listOf(current), current.id)))
            assertEquals(current.id, remote.setQueueCalls.single().items.single().mediaId)
            playback.close()
        }
    }

    private companion object {
        val id = "a".repeat(64)
    }
}
