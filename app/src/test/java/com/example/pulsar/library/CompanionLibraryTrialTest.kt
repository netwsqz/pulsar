package com.example.pulsar.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.pulsar.data.companion.CompanionLibraryConnection
import com.example.pulsar.data.companion.CompanionPairingStore
import com.example.pulsar.data.companion.CompanionLibraryError
import com.example.pulsar.QueueTrack
import com.example.pulsar.libraryGroups
import com.example.pulsar.domain.model.Track
import com.example.pulsar.domain.model.Album
import com.example.pulsar.download.CompanionDownloadCopy
import com.example.pulsar.download.CompanionDownloadedAlbum
import com.example.pulsar.download.LinkedDownload
import com.example.pulsar.offline.OfflineInput
import com.example.pulsar.offline.OfflineNotice
import com.example.pulsar.offline.offlineView
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
    fun `已有配对时离线空态 扫描中 密钥失效和在线空库可区分`() = runTest {
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
            assertTrue(offline.isOffline)
            assertEquals(emptyList<Track>(), offline.tracks)
            assertNull(offline.error)
            assertEquals(false, offline.needsPairing)
            assertEquals("http://192.168.1.8:8900", offline.serviceUrl)

            assertTrue(offline.search.isOfflineSearch)

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

    @Test
    fun `电脑断开时原歌曲和搜索只显示确认的副本 恢复联网后替换文件不再读旧副本`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val store = CompanionPairingStore(preferences)
        val otherId = "b".repeat(64)
        var revision = "c".repeat(64)
        var online = true
        HttpClient(MockEngine {
            if (!online) throw IOException("电脑关机")
            respond("""{"tracks":[{"id":"$id","title":"同名歌","artist":"歌手","album":"专辑","revision":"$revision"},
                {"id":"$otherId","title":"同名歌","artist":"歌手","album":"另一张专辑"}]}""")
        }).use { client ->
            val old = CompanionLibraryConnection(client, store).pair("192.168.1.8", "test-key").getOrThrow().first()
            val original = LinkedDownload(old, "existing:account:copy", "stamp")
            var available = emptyList<LinkedDownload>()
            val trial = CompanionLibraryTrial(
                CompanionLibraryConnection(client, store), backgroundScope, StandardTestDispatcher(testScheduler),
                loadLinked = { available },
            )
            online = false
            trial.enter()
            assertTrue(trial.state.first { !it.isLoading }.tracks!!.isEmpty())
            available = listOf(original)
            trial.reloadLocalDownloads()
            val offline = trial.state.first { it.tracks == listOf(old) }
            assertTrue(offline.isOffline)
            assertEquals(listOf(old), offline.tracks)
            trial.updateSearchQuery("同名歌")
            advanceTimeBy(LIBRARY_SEARCH_DEBOUNCE_MS)
            runCurrent()
            val result = trial.state.value.search.results!!.tracks.single()
            assertEquals(original.playbackUri, trial.streamUrl(result))
            assertTrue(trial.isPlayable(result))

            online = true
            revision = "d".repeat(64)
            trial.retry()
            val refreshed = trial.state.first { !it.isLoading }
            assertEquals(false, refreshed.isOffline)
            val refreshedTracks = checkNotNull(refreshed.tracks)
            assertEquals(2, refreshedTracks.size)
            val changed = refreshedTracks.first()
            assertEquals(old.id, changed.id)
            assertEquals("http://192.168.1.8:8900/api/library/tracks/$id/audio", trial.streamUrl(changed))
        }
    }

    @Test
    fun `已下载列表的电脑曲目断网重开后仍能从歌曲和搜索点播 只显示完整副本`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        var online = true
        val revision = "c".repeat(64)
        HttpClient(MockEngine {
            if (!online) throw IOException("电脑关机")
            respond("""{"tracks":[{"id":"$id","title":"已下载","artist":"歌手","album":"专辑","revision":"$revision"},
                {"id":"${"b".repeat(64)}","title":"未下载","artist":"歌手","album":"专辑","revision":"$revision"},
                {"id":"${"d".repeat(64)}","title":"未标注专辑的歌","artist":"歌手","album":"","revision":"$revision"}]}""")
        }).use { client ->
            val store = CompanionPairingStore(preferences)
            val connection = CompanionLibraryConnection(client, store)
            val catalog = connection.pair("192.168.1.8", "test-key").getOrThrow()
            val saved = catalog.first()
            val pairing = checkNotNull(connection.savedScope())
            val copy = CompanionDownloadCopy(pairing, saved)
            val untagged = CompanionDownloadCopy(pairing, catalog.last())
            val album = Album("2:歌手:专辑", "专辑", "歌手", 2, null)
            val groups = listOf(
                CompanionDownloadedAlbum(pairing, album, listOf(copy), totalCount = 2),
                CompanionDownloadedAlbum(pairing, Album("2:歌手:", "未标注专辑", "歌手", null, null), listOf(untagged), totalCount = 1),
            )
            val restart = CompanionLibraryTrial(
                CompanionLibraryConnection(client, store), backgroundScope, StandardTestDispatcher(testScheduler),
                loadDownloads = { groups },
            )
            online = false
            restart.enter()
            val offline = restart.state.first { !it.isLoading }
            assertTrue(offline.isOffline)
            assertEquals(listOf(saved, untagged.track), offline.tracks)
            val view = offlineView(OfflineInput(
                isOffline = offline.isOffline, downloadedTrackIds = offline.downloadedTrackIds,
                tracks = offline.tracks, albums = offline.albums, discoverAlbums = null, albumTracks = null,
                playlistTracks = null, searchResults = null, tracksLoaded = true, isLoading = false,
                prefilteredAlbumCards = true,
            ))
            assertEquals(OfflineNotice.VisibleOnly, view.notice)
            val albums = checkNotNull(view.visibleLibrary.albums)
            val partial = albums.single { it.name == "专辑" }
            assertEquals(2, partial.trackCount)
            assertEquals(listOf(saved), restart.albumTracks(partial))
            assertEquals(listOf(untagged.track), restart.albumTracks(albums.single { it.name == "未标注专辑" }))
            assertEquals(copy.playbackUri, restart.streamUrl(restart.albumTracks(partial)!!.single()))
            assertEquals(copy.playbackUri, restart.streamUrl(offline.downloads.first().copies.single().savedTrack))
            restart.updateSearchQuery("载")
            advanceTimeBy(LIBRARY_SEARCH_DEBOUNCE_MS)
            runCurrent()
            assertEquals(listOf(saved), restart.state.value.search.results?.tracks)
        }
    }

    @Test
    fun `专辑和艺术家沿用歌曲和搜索的曲目身份 专辑可借同组另一首的内嵌封面`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val coveredId = "b".repeat(64)
        val anotherArtist = "c".repeat(64)
        val revision = "d".repeat(64)
        HttpClient(MockEngine {
            respond("""{"tracks":[{"id":"$id","title":"第一首","artist":"歌手甲","album":"同名专辑","revision":"$revision"},
                {"id":"$coveredId","title":"第二首","artist":"歌手甲","album":"同名专辑","revision":"$revision","has_artwork":true},
                {"id":"$anotherArtist","title":"别人的歌","artist":"歌手乙","album":"同名专辑"}]}""")
        }).use { client ->
            val connection = CompanionLibraryConnection(client, CompanionPairingStore(preferences))
            connection.pair("192.168.1.8", "test-key").getOrThrow()
            val trial = CompanionLibraryTrial(connection, backgroundScope, StandardTestDispatcher(testScheduler))
            trial.enter()
            val loaded = trial.state.first { !it.isLoading }
            val songs = checkNotNull(loaded.tracks)
            val albums = checkNotNull(loaded.albums)
            val album = albums.single { it.artist == "歌手甲" }
            val fromArtist = libraryGroups(albums).single { it.name == "歌手甲" }.albums.single()
            assertEquals(album, fromArtist)
            assertEquals(album, trial.albumFor(songs.first()))
            assertEquals(songs.take(2), trial.albumTracks(album))
            assertEquals(songs.take(2), trial.albumTracks(fromArtist))
            assertNull(trial.trackArtwork(songs[0]))
            assertEquals(trial.trackArtwork(songs[1]), trial.albumArtwork(album))
            assertTrue(checkNotNull(trial.albumArtwork(album)).url.endsWith("/tracks/$coveredId/artwork"))
            assertNull(trial.albumArtwork(albums.single { it.artist == "歌手乙" }))

            trial.updateSearchQuery("第二首")
            advanceTimeBy(LIBRARY_SEARCH_DEBOUNCE_MS)
            runCurrent()
            val search = checkNotNull(trial.state.value.search.results).tracks
            assertEquals(listOf(songs[1]), search)
            val urls = mutableListOf<String>()
            val items = FakeTrackMediaItemFactory { track ->
                trial.streamUrl(track)?.let { url ->
                    urls += url
                    androidx.media3.common.MediaItem.Builder().setMediaId(track.id).build()
                }
            }
            val remote = FakePlaybackRemote().apply { snapshot = RemoteSnapshot(isConnected = true) }
            val playback = PlaybackSession(remote, items, trial, backgroundScope)
            for (trackList in listOf(songs, search, trial.albumTracks(album)!!, trial.albumTracks(fromArtist)!!)) {
                urls.clear()
                assertTrue(playback.dispatch(PlaybackIntent.Play(trackList, songs[1].id)))
                val queue = remote.setQueueCalls.last()
                assertEquals(songs[1].id, queue.items[queue.startIndex].mediaId)
                assertEquals("http://192.168.1.8:8900/api/library/tracks/$coveredId/audio", urls[queue.startIndex])
            }
            playback.close()
        }
    }

    @Test
    fun `从歌曲打开专辑下载只选同一专辑原文件 不把同名专辑和旧副本当下载地址`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val revision = "c".repeat(64)
        HttpClient(MockEngine {
            respond("""{"tracks":[{"id":"$id","title":"原曲","artist":"歌手","album":"专辑","revision":"$revision"},
                {"id":"${"b".repeat(64)}","title":"另一首","artist":"另一位歌手","album":"专辑","revision":"$revision"}]}""")
        }).use { client ->
            val store = CompanionPairingStore(preferences)
            val connection = CompanionLibraryConnection(client, store)
            val saved = connection.pair("192.168.1.8", "test-key").getOrThrow().first()
            val trial = CompanionLibraryTrial(connection, backgroundScope, StandardTestDispatcher(testScheduler),
                loadLinked = { listOf(LinkedDownload(saved, "existing:account:copy", "stamp")) })
            trial.enter()
            trial.state.first { !it.isLoading }
            val album = checkNotNull(trial.albumFor(saved))
            val tracks = checkNotNull(trial.albumTracks(album))
            assertEquals(listOf(saved), tracks)
            assertTrue(trial.streamUrl(saved)!!.startsWith("pulsar-linked:"))
            val target = checkNotNull(trial.downloadTarget(album, tracks))
            assertEquals(listOf(saved to "http://192.168.1.8:8900/api/library/tracks/$id/audio"), target.tracks)
        }
    }

    private companion object {
        val id = "a".repeat(64)
    }
}
