package com.example.pulsar.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.pulsar.DownloadScheduler
import com.example.pulsar.DownloadState
import kotlinx.coroutines.flow.first
import com.example.pulsar.MediaDownloads
import com.example.pulsar.DataStoreDownloadLedgerStore
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionDownloadsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `主动下载后只列出完整的歌 重开后可读原副本 换配对后不可读`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val bytes = PhoneCopies()
        val oldLedger = DataStoreDownloadLedgerStore(preferences)
        val scheduler = DownloadScheduler(backgroundScope, bytes, oldLedger)
        var pairing = "a".repeat(64)
        fun open() = CompanionDownloads(preferences, bytes, scheduler) { pairing }
        val tracks = listOf(track("第一首", 'c'), track("第二首", 'd'))
        val target = CompanionDownloadTarget(pairing, album, tracks.map { it to "http://pc/${it.id}" })
        val downloads = open()
        assertEquals(emptyList<Any>(), downloads.load(pairing))
        downloads.enqueue(target)
        bytes.secondStarted.await()
        val group = open().load(pairing).single()
        assertEquals(listOf("第一首"), group.copies.map { it.track.title })
        assertEquals(2, group.totalCount)
        val uri = group.copies.single().playbackUri
        assertNotNull(open().playbackKey(uri))
        assertEquals(emptyList<Any>(), oldLedger.load().entries)

        pairing = "b".repeat(64)
        assertEquals(emptyList<Any>(), open().load(pairing))
        assertNull(open().playbackKey(uri))
        bytes.finishSecond.complete(Unit)
        runCurrent()
    }

    @Test
    fun `整张删除只按名单 正在播放的版本保留`() = runTest {
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temp.root.resolve("pulsar.preferences_pb")
        }
        val bytes = PhoneCopies().apply { finishSecond.complete(Unit) }
        val scheduler = DownloadScheduler(backgroundScope, bytes, DataStoreDownloadLedgerStore(preferences))
        val pairing = "a".repeat(64)
        val downloads = CompanionDownloads(preferences, bytes, scheduler) { pairing }
        val first = track("第一首", 'c')
        val second = track("第二首", 'd')
        val original = CompanionDownloadTarget(pairing, album, listOf(first to "http://pc/first", second to "http://pc/second"))
        downloads.enqueue(original)
        val completed = scheduler.state.first { states -> states.values.any { it == DownloadState.Downloaded } }
        assertEquals(1, completed.size)
        val group = downloads.load(pairing).single()
        val current = group.copies.first()
        assertEquals(1, downloads.deleteAlbum(pairing, group.album.id, current.savedTrack.id))
        assertEquals(listOf(first), downloads.load(pairing).single().copies.map { it.track })
        assertEquals(false, downloads.deleteTrack(current, current.track.id))
        assertEquals(true, downloads.deleteTrack(current, null))
        assertEquals(emptyList<Any>(), downloads.load(pairing))
    }

    private class PhoneCopies : MediaDownloads {
        val secondStarted = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        private val complete = mutableSetOf<String>()
        override suspend fun hasCompleteCopy(key: String) = key in complete
        override suspend fun usedBytes() = complete.size.toLong()
        override suspend fun completeCount() = complete.size
        override suspend fun completeKeys() = complete.toSet()
        override suspend fun download(url: String, key: String, onProgress: (Long, Long) -> Unit): Boolean {
            if (complete.isNotEmpty()) {
                secondStarted.complete(Unit)
                finishSecond.await()
            }
            complete += key
            onProgress(1, 1)
            return true
        }
        override suspend fun remove(key: String) { complete -= key }
    }

    private val album = Album("专辑", "专辑", "歌手", 2, null)
    private fun track(title: String, id: Char) =
        Track("pc:${"e".repeat(64)}:${id.toString().repeat(64)}", title, "歌手", "专辑", null, null,
            sourceRevision = "f".repeat(64))
}
