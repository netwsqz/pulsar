package com.example.pulsar

import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQAlbumSongDto
import com.example.pulsar.data.qqdownload.QQDownloadAlbumResponse
import com.example.pulsar.data.qqdownload.QQDownloadApi
import com.example.pulsar.data.qqdownload.QQDownloadError
import com.example.pulsar.data.qqdownload.QQDownloadResponse
import com.example.pulsar.data.qqdownload.QQDownloadSettings
import com.example.pulsar.data.qqdownload.QQHealthResponse
import com.example.pulsar.data.qqdownload.QQJobDto
import com.example.pulsar.data.qqdownload.QQLibraryHealth
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
    private val albumTracks = listOf(
        QQAlbumSongDto("000trackA1", "以父之名"),
        QQAlbumSongDto("000trackB2", "懦夫"),
        QQAlbumSongDto("000trackC3", "晴天")
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
    fun `manual credential refresh succeeds and shows feedback`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(health = QQHealthResponse(ok = true, ffmpegReady = true))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.refreshCredential()
        advanceUntilIdle()

        assertEquals(1, api.retryCredentialCalls)
        assertFalse(viewModel.state.value.credentialExpired)
        assertTrue(viewModel.state.value.credentialMessage!!.contains("已刷新"))
    }

    @Test
    fun `manual credential refresh failure does not flag expired`() = runTest {
        // 提取不到新凭证 ≠ 当前凭证过期（常见：电脑上没开 QQ 音乐客户端），
        // 手动刷新失败不该点亮过期横幅，只提示原因。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            health = QQHealthResponse(ok = true, ffmpegReady = true),
            retryCredentialError = QQDownloadError.CredentialExpired("提取失败: QQ 音乐客户端未运行")
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.refreshCredential()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.credentialExpired)
        assertTrue(viewModel.state.value.errorMessage!!.contains("QQ 音乐客户端"))
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

    @Test
    fun `open album picker loads tracks with nothing selected`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        val picker = viewModel.state.value.albumPicker!!
        assertEquals("000MkMni19ClKG", picker.albumMid)
        assertEquals("叶惠美", picker.albumName)
        assertFalse(picker.isLoading)
        assertEquals(albumTracks, picker.tracks)
        // 默认全不选：整张下载有专门入口，这个弹窗就是用来选部分的
        assertTrue(picker.selectedMids.isEmpty())
    }

    @Test
    fun `toggle selects and deselects a track`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackB2")
        viewModel.toggleAlbumTrack("000trackC3")
        assertEquals(setOf("000trackB2", "000trackC3"), viewModel.state.value.albumPicker!!.selectedMids)

        viewModel.toggleAlbumTrack("000trackB2")
        assertEquals(setOf("000trackC3"), viewModel.state.value.albumPicker!!.selectedMids)
    }

    @Test
    fun `select all and clear selection`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.selectAllAlbumTracks()
        assertEquals(setOf("000trackA1", "000trackB2", "000trackC3"), viewModel.state.value.albumPicker!!.selectedMids)

        viewModel.clearAlbumSelection()
        assertTrue(viewModel.state.value.albumPicker!!.selectedMids.isEmpty())
    }

    @Test
    fun `confirm downloads only selected tracks and closes picker`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks, albumCount = 2)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackA1")
        viewModel.toggleAlbumTrack("000trackC3")
        viewModel.confirmAlbumDownload()
        advanceUntilIdle()

        assertEquals(listOf("000MkMni19ClKG"), api.downloadAlbumMids)
        assertEquals(listOf(listOf("000trackA1", "000trackC3")), api.downloadAlbumSelections)
        assertNull(viewModel.state.value.albumPicker)
        assertFalse(viewModel.state.value.isEnqueuingAlbum)
        assertTrue(api.jobsCalls >= 1)
    }

    @Test
    fun `confirm failure keeps picker open with selection`() = runTest {
        // 选了半天不能白选：入队失败要保留弹窗与勾选，修好问题（如刷新凭证）可直接再点。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            albumTracks = albumTracks,
            enqueueAlbumError = QQDownloadError.CredentialExpired("过期")
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackB2")
        viewModel.confirmAlbumDownload()
        advanceUntilIdle()

        val picker = viewModel.state.value.albumPicker!!
        assertEquals(setOf("000trackB2"), picker.selectedMids)
        assertFalse(viewModel.state.value.isEnqueuingAlbum)
        assertTrue(viewModel.state.value.errorMessage!!.contains("凭证"))
    }

    @Test
    fun `dismissing picker drops in-flight track list`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.openAlbumPicker(album)
        viewModel.dismissAlbumPicker()
        advanceUntilIdle()

        // 关掉弹窗后到达的曲目列表必须被丢弃，否则会凭空弹回来
        assertNull(viewModel.state.value.albumPicker)
    }

    @Test
    fun `track load failure keeps picker open for retry`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            albumTracks = albumTracks,
            albumSongsError = QQDownloadError.ServiceUnreachable(IllegalStateException())
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.albumPicker!!.isLoading)
        assertTrue(viewModel.state.value.albumPicker!!.tracks.isEmpty())
        assertTrue(viewModel.state.value.errorMessage!!.contains("连不上"))

        api.albumSongsError = null
        viewModel.retryAlbumTracks()
        advanceUntilIdle()

        assertEquals(albumTracks, viewModel.state.value.albumPicker!!.tracks)
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `whole album download sends no selection`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumCount = 11)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadAlbum(album)
        advanceUntilIdle()

        assertEquals(listOf(null), api.downloadAlbumSelections)
    }

    @Test
    fun `partial album enqueue warns about tracks no longer in the album`() = runTest {
        // 服务端只入队「确实还在这张专辑里」的曲目。勾了 2 首只下 1 首还一声不吭，
        // 用户会在任务列表里对着少掉的那首发懵。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks, albumCount = 1)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackA1")
        viewModel.toggleAlbumTrack("000trackB2")
        viewModel.confirmAlbumDownload()
        advanceUntilIdle()

        assertNull(viewModel.state.value.albumPicker)
        assertTrue(viewModel.state.value.errorMessage!!.contains("1 首"))
    }

    @Test
    fun `full album enqueue reports no missing tracks`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks, albumCount = 2)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackA1")
        viewModel.toggleAlbumTrack("000trackB2")
        viewModel.confirmAlbumDownload()
        advanceUntilIdle()

        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `confirm with empty selection sends no request`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.confirmAlbumDownload()
        advanceUntilIdle()

        assertTrue(api.downloadAlbumMids.isEmpty())
        assertNotNull(viewModel.state.value.albumPicker)
    }

    @Test
    fun `switching album while loading drops the previous list`() = runTest {
        // 专辑 A 的列表还在路上就打开了专辑 B：A 的迟到结果不能写进 B 的列表。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = albumTracks)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.openAlbumPicker(album)
        viewModel.openAlbumPicker(album.copy(albumMid = "000OtherAlbum", albumName = "七里香"))
        advanceUntilIdle()

        val picker = viewModel.state.value.albumPicker!!
        assertEquals("000OtherAlbum", picker.albumMid)
        assertFalse(picker.isLoading)
        assertEquals(albumTracks, picker.tracks)
    }

    @Test
    fun `credential retry reloads an open picker that had no tracks`() = runTest {
        // 弹窗里的「重试提取凭证」成功后要自动把曲目拉回来，否则用户对着空列表
        // 还得自己猜要不要点「重新获取曲目」。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            albumTracks = albumTracks,
            albumSongsError = QQDownloadError.CredentialExpired("过期")
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.credentialExpired)

        api.albumSongsError = null
        viewModel.retryCredential()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.credentialExpired)
        assertEquals(albumTracks, viewModel.state.value.albumPicker!!.tracks)
    }

    // ── 工单 03：搜索行看得见 + 能强制下载 ──────────────────────────

    @Test
    fun `search results merge in-library and downloading marks into global sets`() = runTest {
        // 标记不能挂在单曲对象上（搜索结果整批替换就丢），必须并入以 song_mid 为键的全局集合。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            songs = listOf(
                song.copy(inLibrary = true),
                song.copy(songMid = "000DownMid", title = "七里香", downloading = true)
            )
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.updateSearchQuery("晴天")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.inLibraryMids.contains("0039MnYb0qxYhV"))
        assertTrue(viewModel.state.value.activeDownloadingMids.contains("000DownMid"))
    }

    @Test
    fun `unreachable service keeps every song tappable`() = runTest {
        // 判定不出来一律按「没有」：服务不可达时集合必须为空，所有歌照常可点。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            songs = listOf(song),
            searchError = QQDownloadError.ServiceUnreachable(IllegalStateException())
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.updateSearchQuery("晴天")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.inLibraryMids.isEmpty())
        assertTrue(viewModel.state.value.activeDownloadingMids.isEmpty())
    }

    @Test
    fun `download song passes singer as artist for server-side dedup`() = runTest {
        // 服务端判定「已在库」必须同时有歌名和歌手：App 不传 artist，服务端那层挡重复等于没接上。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi()
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadSong(song)
        advanceUntilIdle()

        assertEquals(listOf("周杰伦"), api.downloadArtists)
        assertEquals(listOf("晴天"), api.downloadTitles)
        assertFalse(api.downloadForces.single())
    }

    @Test
    fun `skipped response marks song in library without error`() = runTest {
        // 服务端回「已跳过」= 已在库，不是错误：静默并入已在库集合让行变灰。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            health = QQHealthResponse(ok = true, ffmpegReady = true),
            enqueueDownloadResult = QQDownloadResponse(jobId = "", skipped = true)
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadSong(song)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.inLibraryMids.contains(song.songMid))
        assertNull(viewModel.state.value.errorMessage)
        assertEquals(0, api.jobsCalls)
    }

    @Test
    fun `redownload prompt confirms with force and dismisses without request`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi()
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.requestRedownload(song)
        assertEquals(song, viewModel.state.value.redownloadPrompt)

        viewModel.dismissRedownload()
        assertNull(viewModel.state.value.redownloadPrompt)
        assertTrue(api.downloadSongMids.isEmpty())

        viewModel.requestRedownload(song)
        viewModel.confirmRedownload()
        advanceUntilIdle()

        assertTrue(api.downloadForces.single())
        assertNull(viewModel.state.value.redownloadPrompt)
    }

    @Test
    fun `job completion marks mid in library and drops downloading tag`() = runTest {
        // 轮询看到 mid 变 done：立刻并入已在库（零额外请求），下载中标记同时消失。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(jobs = listOf(QQJobDto("j1", song.songMid, "flac", "晴天", "running")))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadSong(song)
        advanceTimeBy(1)
        assertTrue(viewModel.state.value.activeDownloadingMids.contains(song.songMid))

        api.jobs = listOf(QQJobDto("j1", song.songMid, "flac", "晴天", "done"))
        advanceTimeBy(2_000)

        assertTrue(viewModel.state.value.inLibraryMids.contains(song.songMid))
        assertFalse(viewModel.state.value.activeDownloadingMids.contains(song.songMid))
    }

    @Test
    fun `health scanning then ready updates library state`() = runTest {
        // 服务刚启动还在扫曲库：显示扫描中；扫完（3 秒重查）后显示目录与歌曲数。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val scanning = QQHealthResponse(
            ok = true, ffmpegReady = true,
            library = QQLibraryHealth(root = "E:\\file\\Te_Music", ready = false, count = 0)
        )
        val ready = scanning.copy(library = QQLibraryHealth(root = "E:\\file\\Te_Music", ready = true, count = 596))
        val api = FakeQQApi(healthSequence = listOf(scanning, ready))
        val viewModel = createViewModel(preferences, api)

        advanceTimeBy(100)
        assertFalse(viewModel.state.value.libraryReady)
        assertEquals("E:\\file\\Te_Music", viewModel.state.value.libraryRoot)

        advanceTimeBy(5_000)
        assertTrue(viewModel.state.value.libraryReady)
        assertEquals(596, viewModel.state.value.libraryCount)
    }

    // ── 工单 04：选曲弹窗与整张下载 ──────────────────────────────

    @Test
    fun `album picker merges marks so select all only picks undownloaded`() = runTest {
        // 「全选未下载」：已在库/下载中的不勾；手动勾过的保留。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            albumTracks = listOf(
                QQAlbumSongDto("000trackA1", "以父之名"),
                QQAlbumSongDto("000trackB2", "懦夫", inLibrary = true),
                QQAlbumSongDto("000trackC3", "晴天", downloading = true)
            )
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.inLibraryMids.contains("000trackB2"))
        viewModel.selectAllAlbumTracks()

        assertEquals(setOf("000trackA1"), viewModel.state.value.albumPicker!!.selectedMids)
    }

    @Test
    fun `picker toggle ignores downloading tracks`() = runTest {
        // 正在下载还勾它没意义：点了也不勾上。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumTracks = listOf(QQAlbumSongDto("000trackC3", "晴天", downloading = true)))
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackC3")

        assertTrue(viewModel.state.value.albumPicker!!.selectedMids.isEmpty())
    }

    @Test
    fun `picker manually checked in-library track is sent explicitly`() = runTest {
        // 勾选本身就是强制：已在库的曲目手动勾上照下，显式 song_mids 服务端不跳过。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(
            albumTracks = listOf(
                QQAlbumSongDto("000trackA1", "以父之名"),
                QQAlbumSongDto("000trackB2", "懦夫", inLibrary = true)
            ),
            albumCount = 2
        )
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()
        viewModel.openAlbumPicker(album)
        advanceUntilIdle()

        viewModel.toggleAlbumTrack("000trackB2")
        viewModel.confirmAlbumDownload()
        advanceUntilIdle()

        assertEquals(listOf(listOf("000trackB2")), api.downloadAlbumSelections)
        assertNull(viewModel.state.value.albumPicker)
    }

    @Test
    fun `picker summary counts in-library tracks`() {
        val tracks = listOf(
            QQAlbumSongDto("a", "甲"),
            QQAlbumSongDto("b", "乙", inLibrary = true),
            QQAlbumSongDto("c", "丙", inLibrary = true)
        )

        assertEquals("共 3 首 · 已有 2 首", albumPickerSummary(tracks, setOf("b", "c")))
    }

    @Test
    fun `whole album download reports skipped count`() = runTest {
        // 整张下载跳过已在库的曲目：必须说出来，不然像按钮坏了。
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumCount = 3, albumSkippedCount = 8)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadAlbum(album)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.errorMessage!!.contains("已跳过 8 首"))
        assertTrue(api.jobsCalls >= 1)
    }

    @Test
    fun `whole album already in library reports nothing new downloaded`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900")
        val api = FakeQQApi(albumCount = 0, albumSkippedCount = 11)
        val viewModel = createViewModel(preferences, api)
        advanceUntilIdle()

        viewModel.downloadAlbum(album)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.errorMessage!!.contains("11"))
        assertFalse(viewModel.state.value.isEnqueuingAlbum)
    }

    @Test
    fun `文件操作密钥启动时载入，保存时写进偏好`() = runTest {
        val preferences = FakePreferences(initialUrl = "http://192.168.1.10:8900", initialKey = "old-key")
        val viewModel = createViewModel(preferences, FakeQQApi())
        advanceUntilIdle()

        assertEquals("old-key", viewModel.state.value.fileOpsKey)

        viewModel.updateFileOpsKeyInput("new-key")
        viewModel.saveFileOpsKey()
        advanceUntilIdle()

        assertEquals("new-key", preferences.savedKey)
        assertEquals("new-key", viewModel.state.value.fileOpsKey)
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
        /** 依次返回的 health 序列（用尽后回退 [health]）；测「索引扫描中 → 就绪」。 */
        var healthSequence: List<QQHealthResponse>? = null,
        var songs: List<QQSongDto> = emptyList(),
        var albums: List<QQAlbumDto> = emptyList(),
        var albumCount: Int = 0,
        var albumSkippedCount: Int = 0,
        var albumTracks: List<QQAlbumSongDto> = emptyList(),
        var albumSongsError: Throwable? = null,
        var enqueueAlbumError: Throwable? = null,
        var enqueueDownloadResult: QQDownloadResponse? = null,
        var enqueueDownloadError: Throwable? = null,
        var jobs: List<QQJobDto> = emptyList(),
        var searchError: Throwable? = null,
        var retryCredentialError: Throwable? = null
    ) : QQDownloadApi {
        var healthCalls = 0
        var searchQueries = mutableListOf<String>()
        var downloadSongMids = mutableListOf<String>()
        var downloadQualities = mutableListOf<String>()
        var downloadTitles = mutableListOf<String?>()
        var downloadArtists = mutableListOf<String?>()
        var downloadForces = mutableListOf<Boolean>()
        var downloadAlbumMids = mutableListOf<String>()

        /** 每次整专入队带的选曲；null = 整张。 */
        var downloadAlbumSelections = mutableListOf<List<String>?>()
        var downloadAlbumForces = mutableListOf<Boolean>()
        var jobsCalls = 0
        var retryCredentialCalls = 0

        override suspend fun health(): Result<QQHealthResponse> {
            healthCalls++
            healthError?.let { return Result.failure(it) }
            val sequence = healthSequence
            val response = if (sequence != null && healthCalls <= sequence.size) sequence[healthCalls - 1] else health
            return Result.success(response)
        }

        override suspend fun search(keyword: String, limit: Int): Result<List<QQSongDto>> {
            searchQueries += keyword
            return searchError?.let { Result.failure(it) } ?: Result.success(songs)
        }

        override suspend fun searchAlbums(keyword: String, limit: Int): Result<List<QQAlbumDto>> =
            searchError?.let { Result.failure(it) } ?: Result.success(albums)

        override suspend fun albumSongs(albumMid: String): Result<List<QQAlbumSongDto>> =
            albumSongsError?.let { Result.failure(it) } ?: Result.success(albumTracks)

        override suspend fun enqueueDownload(
            songMid: String,
            quality: String,
            title: String?,
            artist: String?,
            force: Boolean
        ): Result<QQDownloadResponse> {
            downloadSongMids += songMid
            downloadQualities += quality
            downloadTitles += title
            downloadArtists += artist
            downloadForces += force
            enqueueDownloadError?.let { return Result.failure(it) }
            return Result.success(enqueueDownloadResult ?: QQDownloadResponse(jobId = "job-${downloadSongMids.size}"))
        }

        override suspend fun enqueueAlbum(
            albumMid: String,
            quality: String,
            songMids: List<String>?,
            force: Boolean
        ): Result<QQDownloadAlbumResponse> {
            downloadAlbumMids += albumMid
            downloadAlbumSelections += songMids
            downloadAlbumForces += force
            return enqueueAlbumError?.let { Result.failure(it) }
                ?: Result.success(
                    QQDownloadAlbumResponse(
                        jobIds = List(albumCount) { "job-$it" },
                        count = albumCount,
                        skippedCount = albumSkippedCount
                    )
                )
        }

        override suspend fun jobs(): Result<List<QQJobDto>> {
            jobsCalls++
            return Result.success(jobs)
        }

        override suspend fun retryCredential(): Result<Unit> {
            retryCredentialCalls++
            searchError = null
            retryCredentialError?.let { return Result.failure(it) }
            return Result.success(Unit)
        }
    }

    /** 纯内存 Fake：QQDownloadSettings 是接口，无需碰 DataStore。 */
    private class FakePreferences(
        initialUrl: String = "",
        initialQuality: String = QQDownloadSettings.DEFAULT_QUALITY,
        initialKey: String = ""
    ) : QQDownloadSettings {
        var savedUrl: String? = initialUrl
        var savedKey: String? = initialKey
        private var url: String = initialUrl
        private var qualityValue: String = initialQuality
        private var keyValue: String = initialKey

        override suspend fun serviceUrl(): String = url
        override suspend fun quality(): String = qualityValue
        override suspend fun fileOpsKey(): String = keyValue
        override suspend fun saveServiceUrl(newUrl: String) {
            savedUrl = newUrl
            url = newUrl
        }
        override suspend fun saveQuality(newQuality: String) {
            qualityValue = newQuality
        }

        override suspend fun saveFileOpsKey(key: String) {
            savedKey = key
            keyValue = key
        }
    }
}
