package com.example.pulsar

import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQDownloadApi
import com.example.pulsar.data.qqdownload.QQDownloadError
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import com.example.pulsar.data.qqdownload.QQHealthResponse
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQSongDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QQDownloadViewModelTest {
    private val song = QQSongDto(
        title = "晴天", singer = "周杰伦", album = "叶惠美",
        songMid = "0039MnYb0qxYhV", availableQualities = listOf("flac", "320")
    )
    private val album = QQAlbumDto(
        albumMid = "000MkMni19ClKG", albumName = "叶惠美",
        singer = "周杰伦", songCount = 11
    )

    @Test
    fun `restores saved service url and checks health`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(health = QQHealthResponse(ok = true, ffmpegReady = true))

        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        assertEquals("http://192.168.1.10:8900", viewModel.state.value.serviceUrl)
        assertTrue(viewModel.state.value.serviceReachable)
        assertEquals(1, api.healthCalls)
    }

    @Test
    fun `blank search query clears results without request`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(songs = listOf(song))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.updateSearchQuery("晴天")
        advanceTimeBy(300)
        advanceUntilIdle()
        viewModel.updateSearchQuery("")
        advanceUntilIdle()

        assertTrue(api.searchQueries.isEmpty() || api.searchQueries == listOf("晴天"))
        assertTrue(viewModel.state.value.songs.isEmpty())
        assertFalse(viewModel.state.value.isSearching)
    }

    @Test
    fun `search debounces and cancels stale request`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(songs = listOf(song))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.updateSearchQuery("old")
        advanceTimeBy(100)
        viewModel.updateSearchQuery("latest")
        advanceTimeBy(299)
        assertTrue(api.searchQueries.isEmpty())
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(listOf("latest"), api.searchQueries)
        assertEquals(listOf(song), viewModel.state.value.songs)
    }

    @Test
    fun `search without service url prompts setup`() = runTest {
        val api = FakeQQApi(songs = listOf(song))
        val viewModel = createViewModel(FakePreferences(), api)
        advanceUntilIdle()

        viewModel.updateSearchQuery("晴天")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue(api.searchQueries.isEmpty())
        assertNotNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `download song enqueues with preferred quality and starts polling`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            jobs = listOf(QQJobDto("j1", "0039MnYb0qxYhV", "flac", "晴天", "done"))
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadSong(song)
        advanceUntilIdle()

        assertEquals("0039MnYb0qxYhV", api.downloadSongMids.single())
        assertEquals("flac", api.downloadQualities.single())
        assertEquals(1, viewModel.state.value.jobs.size)
    }

    @Test
    fun `download song falls back to available quality`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi()
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        // 用户偏好 hires，但音源只有 320/128 → 降级到 320
        viewModel.downloadSong(song.copy(availableQualities = listOf("320", "128")))
        advanceUntilIdle()

        assertEquals("320", api.downloadQualities.single())
    }

    @Test
    fun `download album enqueues and polls until done`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumCount = 11)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadAlbum(album)
        advanceUntilIdle()

        assertEquals("000MkMni19ClKG", api.downloadAlbumMids.single())
        assertFalse(viewModel.state.value.isEnqueuingAlbum)
        assertTrue(api.jobsCalls >= 1)
    }

    @Test
    fun `credential expired shows banner and retry recovers`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(songs = listOf(song))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        api.searchError = QQDownloadError.CredentialExpired("过期")
        viewModel.updateSearchQuery("晴天")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.credentialExpired)
        assertTrue(viewModel.state.value.errorMessage!!.contains("凭证"))

        viewModel.retryCredential()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.credentialExpired)
        assertTrue(api.retryCredentialCalls >= 1)
    }

    @Test
    fun `unreachable service shows connect hint`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(healthError = QQDownloadError.ServiceUnreachable(IllegalStateException()))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.serviceReachable)
        assertTrue(viewModel.state.value.errorMessage!!.contains("连不上"))
    }

    @Test
    fun `polling stops when no active jobs remain`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            jobs = listOf(QQJobDto("j1", "0039MnYb0qxYhV", "flac", null, "done"))
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        val callsBefore = api.jobsCalls

        viewModel.downloadSong(song)
        advanceUntilIdle()

        // 首轮 jobs 就没有活跃任务 → 轮询立即退出，不会周期性再拉
        assertEquals(callsBefore + 1, api.jobsCalls)
        advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals(callsBefore + 1, api.jobsCalls)
    }

    @Test
    fun `polling continues while jobs active then stops`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            jobs = listOf(QQJobDto("j1", "0039MnYb0qxYhV", "flac", null, "running"))
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadSong(song)
        // 注意：任务一直 running 时轮询无限继续，advanceUntilIdle 会永远打转，
        // 所以这里用有界的 advanceTimeBy 推进固定轮次。
        advanceTimeBy(1)
        val callsAfterFirst = api.jobsCalls
        assertTrue(callsAfterFirst >= 1)

        advanceTimeBy(2_000)
        assertTrue(api.jobsCalls > callsAfterFirst)

        // 任务完成后轮询退出
        api.jobs = listOf(QQJobDto("j1", "0039MnYb0qxYhV", "flac", null, "done"))
        advanceTimeBy(2_000)
        val callsAfterDone = api.jobsCalls
        advanceTimeBy(10_000)
        assertEquals(callsAfterDone, api.jobsCalls)
    }

    @Test
    fun `connect service validates scheme and persists`() = runTest {
        val preferences = FakePreferences()
        val api = FakeQQApi(health = QQHealthResponse(ok = true, ffmpegReady = true))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.updateServiceUrlInput("192.168.1.10:8900")
        viewModel.connectService()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.errorMessage!!.contains("http://"))

        viewModel.updateServiceUrlInput("http://192.168.1.10:8900/")
        viewModel.connectService()
        advanceUntilIdle()

        assertEquals("http://192.168.1.10:8900", preferences.savedUrl)
        assertEquals("http://192.168.1.10:8900", viewModel.state.value.serviceUrl)
        assertTrue(viewModel.state.value.serviceReachable)
    }

    private fun TestScope.createViewModel(
        preferences: QQDownloadSettings,
        api: FakeQQApi
    ): QQDownloadViewModel = QQDownloadViewModel(
        preferences,
        FakeQQApiFactory(api),
        StandardTestDispatcher(testScheduler)
    )

    private class FakeQQApiFactory(private val api: QQDownloadApi) : QQDownloadApiFactory {
        override fun create(baseUrl: String): QQDownloadApi = api
    }

    private class FakeQQApi(
        var health: QQHealthResponse = QQHealthResponse(),
        var healthError: Throwable? = null,
        var songs: List<QQSongDto> = emptyList(),
        var albums: List<QQAlbumDto> = emptyList(),
        var albumCount: Int = 0,
        var jobs: List<QQJobDto> = emptyList(),
        var searchError: Throwable? = null
    ) : QQDownloadApi {
        var healthCalls = 0
        var searchQueries = mutableListOf<String>()
        var downloadSongMids = mutableListOf<String>()
        var downloadQualities = mutableListOf<String>()
        var downloadAlbumMids = mutableListOf<String>()
        var jobsCalls = 0
        var retryCredentialCalls = 0

        override suspend fun health(): Result<QQHealthResponse> {
            healthCalls++
            return healthError?.let { Result.failure(it) } ?: Result.success(health)
        }

        override suspend fun search(keyword: String, limit: Int): Result<List<QQSongDto>> {
            searchQueries += keyword
            return searchError?.let { Result.failure(it) } ?: Result.success(songs)
        }

        override suspend fun searchAlbums(keyword: String, limit: Int): Result<List<QQAlbumDto>> =
            searchError?.let { Result.failure(it) } ?: Result.success(albums)

        override suspend fun albumSongs(albumMid: String): Result<List<String>> =
            Result.success(emptyList())

        override suspend fun enqueueDownload(songMid: String, quality: String, title: String?): Result<String> {
            downloadSongMids += songMid
            downloadQualities += quality
            return Result.success("job-${downloadSongMids.size}")
        }

        override suspend fun enqueueAlbum(albumMid: String, quality: String): Result<Int> {
            downloadAlbumMids += albumMid
            return Result.success(albumCount)
        }

        override suspend fun jobs(): Result<List<QQJobDto>> {
            jobsCalls++
            return Result.success(jobs)
        }

        override suspend fun retryCredential(): Result<Unit> {
            retryCredentialCalls++
            searchError = null
            return Result.success(Unit)
        }
    }

    /** 纯内存 Fake：QQDownloadSettings 是接口，无需碰 DataStore。 */
    private class FakePreferences(
        initialUrl: String = "",
        initialQuality: String = QQDownloadSettings.DEFAULT_QUALITY
    ) : QQDownloadSettings {
        var savedUrl: String? = initialUrl
        private var url: String = initialUrl
        private var qualityValue: String = initialQuality

        override suspend fun serviceUrl(): String = url
        override suspend fun quality(): String = qualityValue
        override suspend fun saveServiceUrl(newUrl: String) {
            savedUrl = newUrl
            url = newUrl
        }
        override suspend fun saveQuality(newQuality: String) {
            qualityValue = newQuality
        }
    }
}
