package com.example.pulsar

import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinApiFactory
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.data.libraryfiles.LibraryDeleteResponse
import com.example.pulsar.data.libraryfiles.LibraryFailedPath
import com.example.pulsar.data.libraryfiles.LibraryFilesApi
import com.example.pulsar.data.libraryfiles.LibraryFilesError
import com.example.pulsar.data.libraryfiles.LibraryPurgeResponse
import com.example.pulsar.data.libraryfiles.LibraryRecycleFile
import com.example.pulsar.data.libraryfiles.LibraryRecycleResponse
import com.example.pulsar.data.libraryfiles.LibraryRenameResponse
import com.example.pulsar.data.libraryfiles.LibraryRestoreResponse
import com.example.pulsar.download.DownloadLibrary
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.state.DownloadsState
import com.example.pulsar.state.LibrarySectionKey
import com.example.pulsar.state.LibraryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
    fun `启动恢复期间标记恢复中，界面进主页面骨架而非空登录表单`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val viewModel = createViewModel(sessions, FakeJellyfinApi(tracks = listOf(track)))

        // 还没推进调度器：恢复协程尚未跑完。此时 UI 必须知道"正在恢复"，才能
        // 直接进主页面并用灰色波浪骨架占位，而不是先闪一屏空白登录表单。
        assertTrue(viewModel.state.value.isRestoring)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `恢复成功清除恢复中标记并落到曲库`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val viewModel = createViewModel(sessions, FakeJellyfinApi(tracks = listOf(track)))

        advanceUntilIdle()

        assertTrue(!viewModel.state.value.isRestoring)
        assertEquals(listOf(track), viewModel.state.value.tracks)
    }

    @Test
    fun `无保存会话时恢复中标记清除，落到登录表单`() = runTest {
        val viewModel = createViewModel(FakeSessionRepository(null), FakeJellyfinApi())

        advanceUntilIdle()

        assertTrue(!viewModel.state.value.isRestoring)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `恢复失败也要清除恢复中标记，不得停在骨架屏`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(musicItemsError = JellyfinError.Network(IllegalStateException()))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()

        assertTrue(!viewModel.state.value.isRestoring)
        assertNull(viewModel.state.value.tracks)
    }

    @Test
    fun `恢复失败时用已保存的地址与用户名预填登录表单`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(musicItemsError = JellyfinError.Network(IllegalStateException()))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()

        // 连不上服务器时回退到登录表单，但地址/用户名已填好，用户只需补密码重试。
        assertEquals("https://server.test", viewModel.state.value.serverUrl)
        assertEquals("user", viewModel.state.value.username)
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

    // ===== 歌词加载（loadLyricsForCurrentTrack / clearLyrics） =====

    @Test
    fun `loadLyricsForCurrentTrack 请求当前曲目并更新歌词状态`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val synced = Lyrics(
            isSynced = true,
            lines = listOf(com.example.pulsar.data.jellyfin.LyricLine(0L, "第一行"))
        )
        val api = FakeJellyfinApi(lyricsResult = Result.success(synced))
        val viewModel = createViewModel(sessions, api)

        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        assertEquals(listOf("track-1"), api.lyricsTrackIds)
        assertEquals(synced, viewModel.lyrics.value)
    }

    @Test
    fun `切歌后再次加载歌词 最终状态只反映最后一次请求`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi()
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        val trackA = Track("track-a", "A", "Artist", "", 1000L, null)
        val trackB = Track("track-b", "B", "Artist", "", 1000L, null)
        viewModel.setPlaybackQueue(listOf(trackA, trackB), trackA.id)
        viewModel.loadLyricsForCurrentTrack()
        // 第一首响应还没落地就切到 B：A 的请求被取消（不重复发），B 正常发起。
        viewModel.setPlaybackQueue(listOf(trackA, trackB), trackB.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        // A 的请求在启动前就被取消，只有 B 真正发出。
        assertEquals(listOf("track-b"), api.lyricsTrackIds)
    }

    @Test
    fun `无会话时加载歌词不发起请求`() = runTest {
        val api = FakeJellyfinApi()
        val viewModel = createViewModel(FakeSessionRepository(null), api)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        assertEquals(0, api.lyricsCalls)
    }

    @Test
    fun `歌词请求失败静默回退到无歌词`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(lyricsResult = Result.failure(JellyfinError.Network(IOException())))
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()

        assertEquals(Lyrics.NONE, viewModel.lyrics.value)
    }

    @Test
    fun `clearLyrics 清空状态且后续旧响应不写入`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val synced = Lyrics(isSynced = false, lines = emptyList())
        val api = FakeJellyfinApi(lyricsResult = Result.success(synced))
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        // clear 发生在响应落地前（模拟登出），响应必须作废。
        viewModel.clearLyrics()
        advanceUntilIdle()

        assertEquals(Lyrics.NONE, viewModel.lyrics.value)
    }

    @Test
    fun `会话过期清空歌词`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val synced = Lyrics(isSynced = false, lines = emptyList())
        val api = FakeJellyfinApi(lyricsResult = Result.success(synced))
        val authorization = FakeAuthorizationSink("active-auth")
        val viewModel = createViewModel(sessions, api, authorization)
        advanceUntilIdle()

        viewModel.setPlaybackQueue(listOf(track), track.id)
        viewModel.loadLyricsForCurrentTrack()
        advanceUntilIdle()
        assertEquals(synced, viewModel.lyrics.value)

        // 曲库请求撞上 Unauthorized → 走真实会话过期路径，歌词必须随之清空。
        api.albumsError = JellyfinError.Unauthorized()
        viewModel.loadAlbums()
        advanceUntilIdle()

        assertEquals(Lyrics.NONE, viewModel.lyrics.value)
        assertNull(authorization.value)
        assertTrue(sessions.cleared)
    }

    // ── 曲库文件操作（工单 04：删除 / 改名走 PC 伴生服务） ──────────────────

    @Test
    fun `删除成功后本地列表与播放队列都移除这首歌`() = runTest {
        val current = track.copy(filePath = "E:\\music\\a.flac")
        val other = track.copy(id = "track-2", filePath = "E:\\music\\b.flac")
        val api = FakeLibraryFilesApi()
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(current, other))

        viewModel.setPlaybackQueue(listOf(current, other), current.id)
        val ok = viewModel.deleteTrack(other)

        assertTrue(ok)
        // 本地立刻更新，不等 Jellyfin 重扫
        assertEquals(listOf(current), viewModel.state.value.tracks)
        assertNull(viewModel.playbackTracks.value["track-2"])
        assertEquals(listOf(listOf("E:\\music\\b.flac")), api.deletedPaths)
        assertTrue(!viewModel.state.value.isFileOpsBusy)
    }

    @Test
    fun `删除失败时列表不动并给出提示`() = runTest {
        val other = track.copy(id = "track-2", filePath = "E:\\music\\b.flac")
        val api = FakeLibraryFilesApi(
            deleteResult = Result.failure(LibraryFilesError.ServiceUnreachable(IOException()))
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(track, other))

        val ok = viewModel.deleteTrack(other)

        assertTrue(!ok)
        assertEquals(listOf(track, other), viewModel.state.value.tracks)
        assertTrue(viewModel.state.value.fileOpsMessage?.isNotBlank() == true)
    }

    @Test
    fun `正在播的那首删除请求不会发出`() = runTest {
        val current = track.copy(filePath = "E:\\music\\a.flac")
        val api = FakeLibraryFilesApi()
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(current))

        viewModel.setPlaybackQueue(listOf(current), current.id)
        val ok = viewModel.deleteTrack(current)

        assertTrue(!ok)
        assertEquals(0, api.deleteCalls)
        // 文件还在，本地列表也不能动
        assertEquals(listOf(current), viewModel.state.value.tracks)
    }

    @Test
    fun `拿不到文件路径时不发删除请求`() = runTest {
        val api = FakeLibraryFilesApi()
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(track))

        val ok = viewModel.deleteTrack(track)

        assertTrue(!ok)
        assertEquals(0, api.deleteCalls)
    }

    @Test
    fun `改名成功后字段被替换`() = runTest {
        val current = track.copy(filePath = "E:\\music\\a.flac")
        val api = FakeLibraryFilesApi()
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(current))

        viewModel.setPlaybackQueue(listOf(current), current.id)
        val ok = viewModel.renameTrack(current, title = "新歌名", artist = null, album = "新专辑")

        assertTrue(ok)
        // 本地字段被替换，filePath 不变（改名不挪文件）
        assertEquals(current.copy(title = "新歌名", album = "新专辑"), viewModel.state.value.tracks.orEmpty().single())
        // 正在播的这首也要跟着换，播放页头显示新名字
        assertEquals(current.copy(title = "新歌名", album = "新专辑"), viewModel.currentTrack.value)
        assertEquals(1, api.renameCalls)
        // 只动传了的字段：artist 传 null 就不该写
        assertNull(api.renameArtist)
        assertEquals("新歌名", api.renameTitle)
        assertEquals("新专辑", api.renameAlbum)
    }

    @Test
    fun `改名失败时列表保持原名`() = runTest {
        val current = track.copy(filePath = "E:\\music\\a.flac")
        val api = FakeLibraryFilesApi(
            renameResult = Result.failure(LibraryFilesError.Unauthorized("bad key"))
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(current))

        val ok = viewModel.renameTrack(current, title = "新歌名")

        assertTrue(!ok)
        assertEquals(listOf(current), viewModel.state.value.tracks)
        // 密钥错要单独提示去检查，不是笼统的服务出错
        assertTrue(viewModel.state.value.fileOpsMessage?.contains("密钥") == true)
    }

    // ── 回收站（工单 06：设置页看回收站、恢复、清空） ──────────────────────

    @Test
    fun `回收站加载成功进状态`() = runTest {
        val api = FakeLibraryFilesApi(
            recycleResult = Result.success(
                LibraryRecycleResponse(
                    files = listOf(LibraryRecycleFile(relPath = "周杰伦/七里香/a.flac", size = 1024)),
                    totalSize = 1024
                )
            )
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(track))

        viewModel.loadRecycle()
        advanceUntilIdle()

        assertEquals(listOf("周杰伦/七里香/a.flac"), viewModel.state.value.recycleFiles.map { it.relPath })
        assertTrue(!viewModel.state.value.isRecycleLoading)
    }

    @Test
    fun `回收站加载失败给出说得清的提示`() = runTest {
        val api = FakeLibraryFilesApi(
            recycleResult = Result.failure(LibraryFilesError.ServiceUnreachable(IOException()))
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(track))

        viewModel.loadRecycle()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.recycleMessage?.contains("服务") == true)
        assertTrue(viewModel.state.value.recycleFiles.isEmpty())
    }

    @Test
    fun `恢复成功后条目从回收站移除`() = runTest {
        val api = FakeLibraryFilesApi(
            recycleResult = Result.success(
                LibraryRecycleResponse(
                    files = listOf(
                        LibraryRecycleFile(relPath = "a.flac", size = 1),
                        LibraryRecycleFile(relPath = "b.flac", size = 2)
                    ),
                    totalSize = 3
                )
            )
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(track))
        viewModel.loadRecycle()
        advanceUntilIdle()

        viewModel.restoreRecycle("a.flac")
        advanceUntilIdle()

        assertEquals(listOf("b.flac"), viewModel.state.value.recycleFiles.map { it.relPath })
        assertEquals(listOf(listOf("a.flac")), api.restoredPaths)
    }

    @Test
    fun `清空回收站后列表变空`() = runTest {
        val api = FakeLibraryFilesApi(
            recycleResult = Result.success(
                LibraryRecycleResponse(files = listOf(LibraryRecycleFile(relPath = "a.flac", size = 1)))
            )
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(track))
        viewModel.loadRecycle()
        advanceUntilIdle()

        viewModel.purgeRecycle()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.recycleFiles.isEmpty())
        assertEquals(1, api.purgeCalls)
    }

    // ── 批量多选删除（工单 05） ─────────────────────────────────────────

    @Test
    fun `全选只选传入的当前列表，可勾可清`() = runTest {
        val viewModel = createViewModelWithFileOps(FakeLibraryFilesApi(), tracks = listOf(track))

        viewModel.enterMultiSelect("track-1")
        assertEquals(setOf("track-1"), viewModel.state.value.multiSelectIds)
        viewModel.selectAllTracks(listOf("track-1", "track-2", "track-3"))
        assertEquals(setOf("track-1", "track-2", "track-3"), viewModel.state.value.multiSelectIds)
        viewModel.toggleMultiSelect("track-2")
        assertEquals(setOf("track-1", "track-3"), viewModel.state.value.multiSelectIds)
        viewModel.clearMultiSelect()
        assertTrue(viewModel.state.value.multiSelectIds.isEmpty())
    }

    @Test
    fun `批量删除成功后列表被裁剪并退出多选`() = runTest {
        val a = track.copy(id = "a", filePath = "E:\\music\\a.flac")
        val b = track.copy(id = "b", filePath = "E:\\music\\b.flac")
        val c = track.copy(id = "c", filePath = "E:\\music\\c.flac")
        val viewModel = createViewModelWithFileOps(FakeLibraryFilesApi(), tracks = listOf(a, b, c))
        viewModel.setPlaybackQueue(listOf(a, b, c), "a")
        viewModel.enterMultiSelect("b")
        viewModel.selectAllTracks(listOf("a", "b", "c"))

        val deleted = viewModel.deleteTracks(listOf(b, c))

        assertEquals(2, deleted)
        assertEquals(listOf(a), viewModel.state.value.tracks)
        assertNull(viewModel.playbackTracks.value["b"])
        assertNull(viewModel.playbackTracks.value["c"])
        assertTrue(viewModel.state.value.multiSelectIds.isEmpty())
    }

    @Test
    fun `批量删除部分失败时成功的生效且给出提示`() = runTest {
        val a = track.copy(id = "a", filePath = "E:\\music\\a.flac")
        val b = track.copy(id = "b", filePath = "E:\\music\\b.flac")
        val api = FakeLibraryFilesApi(
            deleteResult = Result.success(
                LibraryDeleteResponse(
                    deleted = listOf("a.flac"),
                    failed = listOf(LibraryFailedPath(path = "E:\\music\\b.flac", error = "不是文件"))
                )
            )
        )
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(a, b))
        viewModel.enterMultiSelect("a")

        val deleted = viewModel.deleteTracks(listOf(a, b))

        assertEquals(1, deleted)
        // 成功的生效
        assertEquals(listOf(b), viewModel.state.value.tracks)
        // 失败的有提示
        assertTrue(viewModel.state.value.fileOpsMessage?.contains("失败") == true)
    }

    @Test
    fun `批量删除不碰正在播的那首`() = runTest {
        val current = track.copy(id = "cur", filePath = "E:\\music\\cur.flac")
        val other = track.copy(id = "other", filePath = "E:\\music\\other.flac")
        val api = FakeLibraryFilesApi()
        val viewModel = createViewModelWithFileOps(api, tracks = listOf(current, other))
        viewModel.setPlaybackQueue(listOf(current, other), "cur")

        val deleted = viewModel.deleteTracks(listOf(current, other))

        assertEquals(1, deleted)
        assertEquals(listOf(current), viewModel.state.value.tracks)
        // 只发了正在播之外的那一个路径
        assertEquals(listOf(listOf("E:\\music\\other.flac")), api.deletedPaths)
    }

    // ── 离线与快照 ──────────────────────────────────────────────────

    /**
     * 断网相关测试的时间推进：**必须有界**。探活循环是无限 delay，`advanceUntilIdle`
     * 会把它一直往前推 → 虚拟时间活锁，卡死 gradle daemon（仓库踩过，见 memory）。
     */
    private fun TestScope.advanceBounded() {
        advanceTimeBy(1_000)
    }

    /** 断网测试收尾：取消整个 scope，探活协程随之停。 */
    private fun JellyfinViewModel.stopOfflineForTest() {
        viewModelScope.cancel()
    }

    @Test
    fun `在线请求成功覆写曲库快照`() = runTest {
        val snapshotRepo = FakeSnapshotRepository()
        createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track)),
            snapshotRepository = snapshotRepo
        )
        advanceUntilIdle()
        assertEquals(listOf(track), snapshotRepo.stored?.tracks)
        assertEquals(1, snapshotRepo.saved)
    }

    @Test
    fun `在线成功后快照保留之前拉过的专辑列表（没拉到的屏不丢）`() = runTest {
        val snapshotRepo = FakeSnapshotRepository()
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        snapshotRepo.stored = LibrarySnapshot(albums = listOf(Album("a1", "旧专辑", "手", 1, null)))
        createViewModel(sessions, FakeJellyfinApi(tracks = listOf(track)), snapshotRepository = snapshotRepo)
        advanceUntilIdle()
        assertEquals(listOf("a1"), snapshotRepo.stored?.albums?.map { it.id })
        assertEquals(listOf(track), snapshotRepo.stored?.tracks)
    }

    @Test
    fun `断网启动时用快照撑起界面而非空白`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, snapshotRepository = snapshotRepo
        )
        advanceBounded()
        assertTrue(viewModel.state.value.isOffline)
        assertEquals(listOf(track), viewModel.state.value.tracks)
        // 离线态拿不到服务器，没有错误弹给用户
        assertNull(viewModel.state.value.errorMessage)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `断网且没有快照时照常落到登录表单提示网络错误`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api
        )
        advanceUntilIdle()
        assertTrue(!viewModel.state.value.isOffline)
        assertTrue(viewModel.state.value.errorMessage != null)
    }

    @Test
    fun `离线探活成功后自动回在线，曲库以服务器为准`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, snapshotRepository = snapshotRepo
        )
        advanceBounded()
        assertTrue(viewModel.state.value.isOffline)

        // 网络恢复：探活请求成功
        api.musicItemsError = null
        advanceTimeBy(20_000)
        advanceBounded()

        assertTrue(!viewModel.state.value.isOffline)
        assertNull(viewModel.state.value.cachedTrackIds)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `离线时只有完整副本的歌能播`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        val cached = Track("cached-1", "歌", "手", "辑", 1L, null)
        val uncached = Track("uncached-1", "歌", "手", "辑", 1L, null)
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(cached, uncached))
        val mediaDownloads = FakeMediaDownloads()
        // 缓存键 = streamUrl(mediaId)；Fake 按 id 生成 "stream/{id}"
        mediaDownloads.completeKeys += "stream/cached-1"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo
        )
        advanceBounded()

        // cachedTrackIds 计算完成后：有缓存那首可播，没缓存那首不可播
        advanceBounded()
        assertTrue(viewModel.isPlayable(cached))
        assertTrue(!viewModel.isPlayable(uncached))
        assertEquals("这首歌没有下载，联网后才能播放", viewModel.unplayableReason(uncached))
        assertNull(viewModel.unplayableReason(cached))
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `在线时一律可播（不需要知道缓存状态）`() = runTest {
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track))
        )
        advanceUntilIdle()
        assertTrue(viewModel.isPlayable(track))
        assertNull(viewModel.unplayableReason(track))
    }

    @Test
    fun `缓存层彻底坏掉时离线判定退化为全部不可播，不抛异常`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.failAll = true
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo
        )
        advanceBounded()
        // 判定失败 = 没有缓存能力，全部按不可播处理——不是崩溃
        assertTrue(!viewModel.isPlayable(track))
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `断网时搜索改为快照过滤并标记离线搜索`() = runTest {        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        api.searchError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, snapshotRepository = snapshotRepo
        )
        advanceBounded()

        viewModel.updateSearchQuery("Song")
        advanceTimeBy(400)
        advanceBounded()

        assertTrue(viewModel.state.value.isOfflineSearch)
        assertEquals(listOf(track), viewModel.state.value.searchResults?.tracks)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `断网时点播放列表屏明说需要联网而不是报错`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        api.playlistsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, snapshotRepository = snapshotRepo
        )
        advanceBounded()

        viewModel.loadPlaylists()
        advanceBounded()

        assertTrue(viewModel.state.value.isOffline)
        assertEquals("播放列表需要联网才能查看", viewModel.state.value.errorMessage)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `退出登录清快照但不清下载（下载是用户主动存的）`() = runTest {
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/track-1"
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track)),
            mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo
        )
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        // ADR-0007 / 用户故事 16：主动退出不清下载
        assertEquals(setOf("stream/track-1"), mediaDownloads.completeKeys)
        assertTrue(mediaDownloads.removedKeys.isEmpty())
        assertTrue(snapshotRepo.cleared)
    }

    @Test
    fun `会话过期（token 失效）不清下载也不清快照`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Unauthorized()
        val mediaDownloads = FakeMediaDownloads()
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo
        )
        // 401 → 过期分支（无网络失败，没有常驻探活协程），可以安全推到底
        advanceUntilIdle()

        assertTrue(mediaDownloads.removedKeys.isEmpty())
        assertTrue(!snapshotRepo.cleared)
    }

    // ── 工单 08：离线口径 = 字节真相 ∩ 名单可见性；已下载列表清单 ──────────

    private fun downloadedEntry(
        trackIds: List<String>,
        userId: String = "user-1",
        serverUrl: String = "https://server.test",
        kind: DownloadKind = DownloadKind.ALBUM,
        id: String = "album-1",
        name: String = "Album"
    ) = DownloadEntry(serverUrl, userId, kind, id, name, trackIds, 1L)

    @Test
    fun `离线时字节完整且在当前账号名单里的歌才可播`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val inLedger = Track("in-ledger", "歌", "手", "辑", 1L, null)
        val notInLedger = Track("not-in-ledger", "歌", "手", "辑", 1L, null)
        val snapshotRepo = FakeSnapshotRepository().apply {
            stored = LibrarySnapshot(tracks = listOf(inLedger, notInLedger))
        }
        val mediaDownloads = FakeMediaDownloads().apply {
            // 两首字节都完整：差别只在名单
            completeKeys += "stream/in-ledger"
            completeKeys += "stream/not-in-ledger"
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("in-ledger")))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger
        )
        advanceBounded()

        assertTrue(viewModel.isPlayable(inLedger))
        assertFalse(viewModel.isPlayable(notInLedger))
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `离线时名单里有但字节不完整的歌不可播（判定仍是字节真相）`() = runTest {        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val half = Track("half", "歌", "手", "辑", 1L, null)
        val snapshotRepo = FakeSnapshotRepository().apply { stored = LibrarySnapshot(tracks = listOf(half)) }
        val ledger = FakeDownloadLedgerStore().apply { entries = listOf(downloadedEntry(listOf("half"))) }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = FakeMediaDownloads(), snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger
        )
        advanceBounded()

        assertFalse(viewModel.isPlayable(half))
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `离线时界面派生的曲库只剩已下载（ADR-0008）`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val kept = Track("kept", "留下的", "手", "辑", 1L, null)
        val dropped = Track("dropped", "没下载的", "手", "辑", 1L, null)
        val snapshotRepo = FakeSnapshotRepository().apply {
            stored = LibrarySnapshot(
                tracks = listOf(kept, dropped),
                albums = listOf(Album("al", "辑", "手", trackCount = 2, imageTag = null))
            )
        }
        val mediaDownloads = FakeMediaDownloads().apply { completeKeys += "stream/kept" }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("kept")))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger
        )
        advanceBounded()

        val state = viewModel.state.value
        // 原始状态保留全量（回在线要用），界面派生的那一份才筛。
        assertEquals(listOf(kept, dropped), state.tracks)
        val visible = offlineVisibleLibrary(
            tracks = state.tracks,
            albums = state.albums,
            visibleTrackIds = state.cachedTrackIds.orEmpty()
        )
        assertEquals(listOf(kept), visible.tracks)
        // 专辑只下了一首也还在（选曲下载不该让整张消失）
        assertEquals(listOf("al"), visible.albums?.map { it.id })
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `回在线后派生列表恢复全量，不需要重拉`() = runTest {
        val api = FakeJellyfinApi(listOf(track))
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository().apply {
            stored = LibrarySnapshot(tracks = listOf(track))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, snapshotRepository = snapshotRepo
        )
        advanceBounded()
        assertTrue(viewModel.state.value.isOffline)

        api.musicItemsError = null
        advanceTimeBy(20_000)
        advanceBounded()

        val state = viewModel.state.value
        assertFalse(state.isOffline)
        // 回在线时 cachedTrackIds 清成 null：界面不筛，直接显示原件全量。
        assertNull(state.cachedTrackIds)
        assertEquals(listOf(track), state.tracks)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `换账号后看不见上一个账号的下载（字节还在，但名单不归我）`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository().apply { stored = LibrarySnapshot(tracks = listOf(track)) }
        val mediaDownloads = FakeMediaDownloads().apply { completeKeys += "stream/track-1" }
        // 名单只有 user-1 的条目；当前会话是 user-2
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("track-1"), userId = "user-1"))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-2", "user2", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger
        )
        advanceBounded()

        assertFalse(viewModel.isPlayable(track))
        viewModel.stopOfflineForTest()
    }

    // ── 工单 #4：DownloadLibrary 接线后，离线口径与改前一致 ───────────────

    @Test
    fun `接线下载模块后离线可见集合仍为字节完整且在当前账号名单里`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val inLedger = Track("in-ledger", "歌", "手", "辑", 1L, null)
        val notInLedger = Track("not-in-ledger", "歌", "手", "辑", 1L, null)
        val snapshotRepo = FakeSnapshotRepository().apply {
            stored = LibrarySnapshot(tracks = listOf(inLedger, notInLedger))
        }
        val mediaDownloads = FakeMediaDownloads().apply {
            completeKeys += setOf("stream/in-ledger", "stream/not-in-ledger")
        }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("in-ledger")))
        }
        val library = downloadLibraryFor(mediaDownloads, ledger)
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger, downloadLibrary = library
        )
        advanceBounded()

        assertTrue(viewModel.isPlayable(inLedger))
        assertFalse(viewModel.isPlayable(notInLedger))
        // 交集在 module 里只算了一份；ViewModel 只读它的结果。
        assertEquals(setOf("in-ledger"), library.state.value.visibleTrackIds)
        assertEquals(setOf("stream/in-ledger", "stream/not-in-ledger"), library.state.value.completeKeys)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `接线下载模块后换账号看不见上一个账号的下载`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository().apply { stored = LibrarySnapshot(tracks = listOf(track)) }
        val mediaDownloads = FakeMediaDownloads().apply { completeKeys += "stream/track-1" }
        // 名单只有 user-1 的条目；当前会话是 user-2
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("track-1"), userId = "user-1"))
        }
        val library = downloadLibraryFor(mediaDownloads, ledger)
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-2", "user2", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger, downloadLibrary = library
        )
        advanceBounded()

        assertFalse(viewModel.isPlayable(track))
        assertTrue(library.state.value.visibleTrackIds.isEmpty())
        // ADR-0007：字节仍共享，换账号不删任何东西
        assertEquals(setOf("stream/track-1"), library.state.value.completeKeys)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `退出登录后重新登录，下载还在且离线能放`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-1", "token-1"))
        val mediaDownloads = FakeMediaDownloads().apply { completeKeys += "stream/track-1" }
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("track-1")))
        }
        val snapshotRepo = FakeSnapshotRepository()
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads, snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger
        )
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()
        assertEquals(setOf("stream/track-1"), mediaDownloads.completeKeys)
        // 退出登录不清名单（启动迁移可能再补一条专辑条目，所以只断言原来那条还在）
        assertTrue(ledger.entries.any { it.trackIds.contains("track-1") })

        // 重登成功后网又断了：字节 + 名单都还在，离线照常能放
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user")
        viewModel.updatePassword("pass")
        viewModel.login()
        advanceUntilIdle()
        assertTrue(!viewModel.state.value.isOffline)

        api.albumsError = JellyfinError.Network(IOException("no network"))
        viewModel.loadAlbums()
        advanceBounded()

        assertTrue(viewModel.state.value.isOffline)
        assertTrue(viewModel.isPlayable(track))
        // 重登后迁移会补一条专辑条目（存量副本反推），原来那条下载记录不能丢
        assertTrue(ledger.entries.any { it.trackIds.contains("track-1") })
        viewModel.stopOfflineForTest()
    }

    private val offlinePlaylist = Playlist("pl-1", "通勤", 1, null)
    private val notDownloadedPlaylist = Playlist("pl-2", "别人的列表", 1, null)

    private fun snapshotWithDownloadedPlaylist(
        userId: String = "user-1",
        serverUrl: String = "https://server.test",
        tracks: List<Track> = listOf(track)
    ) = LibrarySnapshot(
        tracks = listOf(track),
        downloadedPlaylists = listOf(
            DownloadedPlaylist(
                id = offlinePlaylist.id,
                name = offlinePlaylist.name,
                serverUrl = serverUrl,
                userId = userId,
                trackCount = tracks.size,
                tracks = tracks
            )
        )
    )

    @Test
    fun `离线时已下载的播放列表能打开，曲目来自本地清单`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                downloadedEntry(listOf("track-1"), kind = DownloadKind.PLAYLIST, id = "pl-1", name = "通勤")
            )
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = FakeMediaDownloads().apply { completeKeys += "stream/track-1" },
            snapshotRepository = FakeSnapshotRepository().apply { stored = snapshotWithDownloadedPlaylist() },
            downloadLedgerStore = ledger
        )
        advanceBounded()

        viewModel.loadPlaylists()
        advanceBounded()
        assertEquals(listOf(offlinePlaylist.id), viewModel.state.value.playlists?.map { it.id })

        viewModel.loadPlaylistTracks(offlinePlaylist)
        advanceBounded()
        assertEquals(listOf(track), viewModel.state.value.playlistTracks)
        assertNull(viewModel.state.value.errorMessage)
        assertTrue(viewModel.isPlayable(track))
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `离线时没下载过的播放列表照旧需要联网`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                downloadedEntry(listOf("track-1"), kind = DownloadKind.PLAYLIST, id = "pl-1", name = "通勤")
            )
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = FakeMediaDownloads(),
            snapshotRepository = FakeSnapshotRepository().apply { stored = snapshotWithDownloadedPlaylist() },
            downloadLedgerStore = ledger
        )
        advanceBounded()

        viewModel.loadPlaylistTracks(notDownloadedPlaylist)
        advanceBounded()

        assertNull(viewModel.state.value.playlistTracks)
        assertEquals("这个播放列表没有下载，需要联网才能查看", viewModel.state.value.errorMessage)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `已下载列表清单按账号过滤，换账号看不见别人的列表`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        // 快照里躺着 user-1 的清单；当前会话是 user-2
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                downloadedEntry(listOf("track-1"), userId = "user-2", kind = DownloadKind.PLAYLIST, id = "pl-1", name = "通勤")
            )
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-2", "user2", "secret")),
            api, mediaDownloads = FakeMediaDownloads(),
            snapshotRepository = FakeSnapshotRepository().apply { stored = snapshotWithDownloadedPlaylist(userId = "user-1") },
            downloadLedgerStore = ledger
        )
        advanceBounded()

        viewModel.loadPlaylists()
        advanceBounded()

        // 一个可见的都没有：保持 null，界面照旧「需要联网」，不误报「暂无播放列表」
        assertNull(viewModel.state.value.playlists)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `在线打开已下载的列表详情会写进已下载列表清单`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        api.playlistTracksResult = Result.success(listOf(track))
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                downloadedEntry(listOf("track-1"), kind = DownloadKind.PLAYLIST, id = "pl-1", name = "通勤")
            )
        }
        val snapshotRepo = FakeSnapshotRepository()
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = FakeMediaDownloads(), snapshotRepository = snapshotRepo,
            downloadLedgerStore = ledger
        )
        advanceUntilIdle()

        viewModel.loadPlaylistTracks(offlinePlaylist)
        advanceUntilIdle()

        val listing = snapshotRepo.stored?.downloadedPlaylists?.single()
        assertEquals("pl-1", listing?.id)
        assertEquals(listOf(track), listing?.tracks)
        assertEquals("user-1", listing?.userId)
    }

    @Test
    fun `没下载过的列表在线打开不会写进已下载列表清单`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        api.playlistTracksResult = Result.success(listOf(track))
        val snapshotRepo = FakeSnapshotRepository()
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = FakeMediaDownloads(), snapshotRepository = snapshotRepo,
            downloadLedgerStore = FakeDownloadLedgerStore()
        )
        advanceUntilIdle()

        viewModel.loadPlaylistTracks(offlinePlaylist)
        advanceUntilIdle()

        assertTrue(snapshotRepo.stored?.downloadedPlaylists.isNullOrEmpty())
    }

    @Test
    fun `换账号重新登录后快照整份替换，不残留旧账号数据`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        val snapshotRepo = FakeSnapshotRepository()
        // 上一账号留下的专辑列表（token 过期不清快照是铁律 5，所以它还在）
        snapshotRepo.stored = LibrarySnapshot(
            tracks = listOf(track),
            albums = listOf(Album("old-account-album", "别人的专辑", "别人", 9, null))
        )
        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-2", "token-2"))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, snapshotRepository = snapshotRepo
        )
        advanceUntilIdle()
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user-2")
        viewModel.updatePassword("pass-2")
        viewModel.login()
        advanceUntilIdle()

        // 新账号的快照里只有新账号的曲库，旧账号的专辑一个字段都不留
        assertTrue(snapshotRepo.stored?.albums.isNullOrEmpty())
        assertEquals(listOf(track), snapshotRepo.stored?.tracks)
    }

    // ===== 服务器地址历史 =====

    @Test
    fun `登录成功把地址记进历史并刷新状态`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-1", "token-1"))
        val history = FakeServerUrlHistory()
        val viewModel = createViewModel(
            FakeSessionRepository(null), api, serverUrlHistory = history
        )
        advanceUntilIdle()
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user")
        viewModel.updatePassword("pass")
        viewModel.login()
        advanceUntilIdle()

        assertEquals(listOf("https://server.test"), history.recorded)
        assertEquals(listOf("https://server.test"), viewModel.state.value.serverUrlHistory)
    }

    @Test
    fun `登录失败不记录地址历史`() = runTest {
        val history = FakeServerUrlHistory()
        val viewModel = createViewModel(
            FakeSessionRepository(null), FakeJellyfinApi(), serverUrlHistory = history
        )
        advanceUntilIdle()
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user")
        viewModel.updatePassword("pass")
        viewModel.login()
        advanceUntilIdle()

        assertTrue(history.recorded.isEmpty())
    }

    @Test
    fun `启动时载入地址历史到状态`() = runTest {
        val history = FakeServerUrlHistory()
        val viewModel = createViewModel(
            FakeSessionRepository(null), FakeJellyfinApi(), serverUrlHistory = history
        )
        history.record("https://old.test")
        advanceUntilIdle()

        assertEquals(listOf("https://old.test"), viewModel.state.value.serverUrlHistory)
    }

    /**
     * 回归：地址历史的读盘和会话恢复是 init 里两个并发协程，**都在真 DataStore 上挂起**。
     *
     * 曾经的写法是 `_state.value = _state.value.copy(serverUrlHistory = load())`——
     * Kotlin 先求 receiver 再求参数，所以 `_state.value` 在进入挂起点**之前**就被求值成
     * 一份「serverUrl 还是空串」的快照。等读盘回来执行 copy 时，会话恢复早已把
     * `serverUrl` 写好，这一份旧快照又把它盖回空串。
     *
     * 后果不是界面难看而已：`serverUrl` 是下载名单的归属键，空了以后
     * `entriesFor(真实地址, userId)` 一条都匹配不上，「已下载」页永远是空的；
     * 专辑页的下载按钮也因为 `buildDownloadTarget` 里 `serverUrl.isBlank()` 而一直禁用。
     *
     * 测试要点：`FakeServerUrlHistory.loadGate` 必须真的挂起，否则复现不出这个交错。
     */
    @Test
    fun `地址历史读盘挂起期间会话恢复写入的地址不被旧快照覆盖`() = runTest {
        val history = FakeServerUrlHistory()
        history.record("https://old.test")
        val historyGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        history.loadGate = historyGate
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        // 会话读盘也卡住：这样才能让两个协程真正交错，复现真 DataStore 下的挂起点。
        val sessionGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        sessions.loadGate = sessionGate
        val viewModel = createViewModel(sessions, FakeJellyfinApi(tracks = listOf(track)), serverUrlHistory = history)

        // 两个协程此刻都挂在盘上；地址历史那条**已经**读到了 `_state.value`
        // （那时 serverUrl 还是空串），正卡在 load() 里面。
        advanceUntilIdle()

        // 先放行会话恢复：它会写 serverUrl 和 tracks。
        sessionGate.complete(Unit)
        advanceUntilIdle()
        assertEquals("https://server.test", viewModel.state.value.serverUrl)
        assertEquals(listOf(track), viewModel.state.value.tracks)

        // 再放行地址历史读盘。它带着「serverUrl 为空」的旧快照回来，不能把新状态盖掉。
        historyGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("https://old.test"), viewModel.state.value.serverUrlHistory)
        assertEquals("https://server.test", viewModel.state.value.serverUrl)
        assertEquals(listOf(track), viewModel.state.value.tracks)
    }

    @Test
    fun `退出登录保留地址历史（本地数据，与账号无关）`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-1", "token-1"))
        val history = FakeServerUrlHistory()
        val viewModel = createViewModel(
            FakeSessionRepository(null), api, serverUrlHistory = history
        )
        advanceUntilIdle()
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user")
        viewModel.updatePassword("pass")
        viewModel.login()
        advanceUntilIdle()
        assertEquals(listOf("https://server.test"), viewModel.state.value.serverUrlHistory)

        viewModel.signOut()
        advanceUntilIdle()

        assertEquals(listOf("https://server.test"), viewModel.state.value.serverUrlHistory)
    }

    @Test
    fun `探活撞上 token 失效走会话过期，不空转到上限`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Network(IOException("no network"))
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val authorization = FakeAuthorizationSink()
        val viewModel = createViewModel(sessions, api, authorization, snapshotRepository = snapshotRepo)
        advanceBounded()
        assertTrue(viewModel.state.value.isOffline)

        // 网络恢复了，但 token 已被服务端吊销：探活必须如实过期，而不是每 15s 空转
        api.musicItemsError = JellyfinError.Unauthorized()
        advanceTimeBy(OFFLINE_RETRY_INTERVAL_MS * 2 + 1_000)
        advanceBounded()

        assertTrue(sessions.cleared)
        assertNull(authorization.value)
        assertTrue(!viewModel.state.value.isOffline)
        assertEquals("登录已失效，请重新登录", viewModel.state.value.errorMessage)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `已下载页按专辑列出完整副本的进度`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
        val t3 = Track("t3", "Song B1", "Artist Y", "Album B", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 2, null)
        val albumB = Album("a2", "Album B", "Artist Y", 1, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        mediaDownloads.completeKeys += "stream/t3"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(t1, t2, t3), albums = listOf(albumA, albumB)),
            mediaDownloads = mediaDownloads
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        viewModel.loadCachedAlbums()
        advanceUntilIdle()

        assertEquals(
            listOf(
                CachedAlbum(albumA, tracks = listOf(t1), totalCount = 2),
                CachedAlbum(albumB, tracks = listOf(t3), totalCount = 1)
            ),
            viewModel.state.value.cachedAlbums
        )
    }

    @Test
    fun `专辑页没加载过时进已下载页会先拉专辑再分组`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 1, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(t1), albums = listOf(albumA)),
            mediaDownloads = mediaDownloads
        )
        advanceUntilIdle()
        // 不先逛专辑页：直接进已下载页（快照里也没有专辑列表）
        viewModel.loadCachedAlbums()
        advanceUntilIdle()

        assertEquals(
            listOf(CachedAlbum(albumA, tracks = listOf(t1), totalCount = 1)),
            viewModel.state.value.cachedAlbums
        )
    }

    @Test
    fun `按专辑删下载：正在播的保留，其余删掉，统计与列表同步刷新`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 2, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        mediaDownloads.completeKeys += "stream/t2"
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("t1", "t2"), id = "a1", name = "Album A"))
        }
        // 统计来自 module 快照（#7）：ViewModel 不再自己查字节层。
        val library = downloadLibraryFor(mediaDownloads, ledger)
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(t1, t2), albums = listOf(albumA)),
            mediaDownloads = mediaDownloads, downloadLedgerStore = ledger, downloadLibrary = library
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(t1, t2), "t1")

        viewModel.deleteAlbumCopies(albumA)
        advanceUntilIdle()

        assertEquals(setOf("stream/t1"), mediaDownloads.completeKeys)
        assertTrue(mediaDownloads.removedKeys.contains("stream/t2"))
        assertEquals(1, library.state.value.stats.completeCount)
        // 提示报的是**真删掉的副本数**：正在播的那首被跳过，所以是 1 不是 2（用户故事 62）
        assertEquals("已删除 1 首", viewModel.state.value.downloadDeleteMessage)
        assertEquals(
            listOf(CachedAlbum(albumA, tracks = listOf(t1), totalCount = 2)),
            viewModel.state.value.cachedAlbums
        )
    }

    @Test
    fun `按专辑删下载：删除期间置忙碌位，删完给「已删除 N 首」`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 2, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += listOf("stream/t1", "stream/t2")
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("t1", "t2"), id = "a1", name = "Album A"))
        }
        val viewModel = createDeleteTestViewModel(
            FakeJellyfinApi(tracks = listOf(t1, t2), albums = listOf(albumA)),
            mediaDownloads, ledger
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        viewModel.deleteAlbumCopies(albumA)
        // 忙碌位在**发起线程**同步置位：界面这一帧就能显示进度、禁掉按钮（连点进不来）
        assertTrue(viewModel.state.value.isDeletingDownload)
        assertNull(viewModel.state.value.downloadDeleteMessage)

        advanceUntilIdle()
        assertFalse(viewModel.state.value.isDeletingDownload)
        assertEquals("已删除 2 首", viewModel.state.value.downloadDeleteMessage)
        // 界面弹完自己清掉，避免转屏/返回后再弹一次
        viewModel.dismissDownloadDeleteMessage()
        assertNull(viewModel.state.value.downloadDeleteMessage)
    }

    @Test
    fun `按专辑删下载：连点第二次直接返回，不重复删也不重复报`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 1, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("t1"), id = "a1", name = "Album A"))
        }
        val viewModel = createDeleteTestViewModel(
            FakeJellyfinApi(tracks = listOf(t1), albums = listOf(albumA)),
            mediaDownloads, ledger
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        viewModel.deleteAlbumCopies(albumA)
        viewModel.deleteAlbumCopies(albumA)
        advanceUntilIdle()

        // 第二次被守卫挡在门外：只剩第一次那一遍的删除记录
        assertEquals(listOf("stream/t1"), mediaDownloads.removedKeys)
        assertEquals("已删除 1 首", viewModel.state.value.downloadDeleteMessage)
        assertFalse(viewModel.state.value.isDeletingDownload)
    }

    @Test
    fun `删到一半退出登录：提示不落到新会话上，字节照删`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 1, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("t1"), id = "a1", name = "Album A"))
        }
        val viewModel = createDeleteTestViewModel(
            FakeJellyfinApi(tracks = listOf(t1), albums = listOf(albumA)),
            mediaDownloads, ledger
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        // 把删除卡在「算完缓存键、正删字节」的一刻——真机上这一段也会被会话失效插队
        val gate = CompletableDeferred<Unit>()
        mediaDownloads.removeGate = gate
        viewModel.deleteAlbumCopies(albumA)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isDeletingDownload)

        viewModel.signOut()
        gate.complete(Unit)
        advanceUntilIdle()

        // 字节照删（本地数据，与界面在展示哪个会话无关）……
        assertTrue(mediaDownloads.removedKeys.contains("stream/t1"))
        // ……但结果不写进新会话的 state：提示不该飘在登录页上
        assertFalse(viewModel.state.value.isDeletingDownload)
        assertNull(viewModel.state.value.downloadDeleteMessage)
    }

    @Test
    fun `整张专辑删光后从已下载列表消失`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t3 = Track("t3", "Song B1", "Artist Y", "Album B", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 1, null)
        val albumB = Album("a2", "Album B", "Artist Y", 1, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        mediaDownloads.completeKeys += "stream/t3"
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                downloadedEntry(listOf("t1"), id = "a1", name = "Album A"),
                downloadedEntry(listOf("t3"), id = "a2", name = "Album B")
            )
        }
        val viewModel = createDeleteTestViewModel(
            FakeJellyfinApi(tracks = listOf(t1, t3), albums = listOf(albumA, albumB)),
            mediaDownloads, ledger
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()

        viewModel.deleteAlbumCopies(albumB)
        advanceUntilIdle()

        assertTrue(mediaDownloads.removedKeys.contains("stream/t3"))
        assertEquals(
            listOf(CachedAlbum(albumA, tracks = listOf(t1), totalCount = 1)),
            viewModel.state.value.cachedAlbums
        )
    }

    @Test
    fun `整张删按名单删：服务器后来新增的歌不在名单里，不跟着删`() = runTest {
        val old1 = Track("old1", "老歌 1", "Artist X", "Album A", 1000L, null)
        val old2 = Track("old2", "老歌 2", "Artist X", "Album A", 1000L, null)
        // 服务器后加的：用户没下过，只是列表里有
        val added = Track("added", "新歌", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 3, null)
        val mediaDownloads = FakeMediaDownloads()
        // 三首都有完整副本（新歌是自动缓存时代留下的字节，模拟「盘上有但名单里没有」）
        mediaDownloads.completeKeys += listOf("stream/old1", "stream/old2", "stream/added")
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(
                DownloadEntry(
                    serverUrl = "https://server.test", userId = "user-1", kind = DownloadKind.ALBUM,
                    id = "a1", name = "Album A", trackIds = listOf("old1", "old2"), createdAt = 1L
                )
            )
        }
        val viewModel = createDeleteTestViewModel(
            FakeJellyfinApi(tracks = listOf(old1, old2, added), albums = listOf(albumA)),
            mediaDownloads, ledger
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        viewModel.deleteAlbumCopies(albumA)
        advanceUntilIdle()

        // 用户故事 10：只删当初下的那批
        assertEquals(setOf("stream/added"), mediaDownloads.completeKeys)
        assertEquals(listOf("stream/old1", "stream/old2"), mediaDownloads.removedKeys)
        // 名单跟着清（跟专辑页的删除按钮同一口径）：删干净就整条移除
        assertTrue(ledger.entries.none { it.id == "a1" })
    }

    @Test
    fun `展开后删单首：只删这一首，正在播的删不动`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 2, null)
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/t1"
        mediaDownloads.completeKeys += "stream/t2"
        val ledger = FakeDownloadLedgerStore().apply {
            entries = listOf(downloadedEntry(listOf("t1", "t2"), id = "a1", name = "Album A"))
        }
        val viewModel = createDeleteTestViewModel(
            FakeJellyfinApi(tracks = listOf(t1, t2), albums = listOf(albumA)),
            mediaDownloads, ledger
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(t1, t2), "t1")

        viewModel.deleteDownloadedTrack(t2)
        advanceUntilIdle()
        assertEquals(listOf("stream/t2"), mediaDownloads.removedKeys)

        // 正在播的那首：ViewModel 侧也挡一道，不指望界面永远记得禁按钮
        viewModel.deleteDownloadedTrack(t1)
        advanceUntilIdle()
        assertEquals(listOf("stream/t2"), mediaDownloads.removedKeys)
        assertEquals(setOf("stream/t1"), mediaDownloads.completeKeys)
        assertEquals(
            listOf(CachedAlbum(albumA, tracks = listOf(t1), totalCount = 2)),
            viewModel.state.value.cachedAlbums
        )
    }

    @Test
    fun `换账号重新登录后已下载列表清空，不残留旧账号的分组`() = runTest {
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/track-1"
        val api = FakeJellyfinApi(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaDownloads = mediaDownloads
        )
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()
        // 切换前：旧账号确实看得到自己的下载。专辑列表没加载时从曲目反推卡片
        // （见 groupCachedByAlbum），所以这里不是空列表——本用例只关心「切换后清空」。
        assertEquals(1, viewModel.state.value.cachedAlbums?.size)

        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-2", "token-2"))
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user-2")
        viewModel.updatePassword("pass-2")
        viewModel.login()
        advanceUntilIdle()

        assertNull(viewModel.state.value.cachedAlbums)
    }

    @Test
    fun `删歌成功后顺带删掉这首的本地副本`() = runTest {
        val mediaDownloads = FakeMediaDownloads()
        val fileOps = FakeLibraryFilesApi()
        val deletable = track.copy(id = "t1", filePath = "E:\\music\\t1.flac")
        val viewModel = createViewModelWithFileOps(
            fileOps, tracks = listOf(deletable),
            mediaDownloads = mediaDownloads
        )

        viewModel.deleteTrack(deletable)

        advanceUntilIdle()
        assertTrue(viewModel.state.value.tracks.isNullOrEmpty())
        assertEquals(listOf("stream/t1"), mediaDownloads.removedKeys)
    }

    @Test
    fun `改名成功不动下载（副本照样能放）`() = runTest {
        val mediaDownloads = FakeMediaDownloads()
        val fileOps = FakeLibraryFilesApi()
        val renameable = track.copy(id = "t1", filePath = "E:\\music\\t1.flac")
        val viewModel = createViewModelWithFileOps(
            fileOps, tracks = listOf(renameable),
            mediaDownloads = mediaDownloads
        )

        viewModel.renameTrack(renameable, title = "新名字")

        advanceUntilIdle()
        // 工单 06：改名改的是内嵌标签，歌没变，下载一个字节都不动
        assertTrue(mediaDownloads.removedKeys.isEmpty())
    }

    @Test
    fun `下载统计照旧：占用与首数来自下载层`() = runTest {
        val mediaDownloads = FakeMediaDownloads()
        mediaDownloads.completeKeys += "stream/track-1"
        val ledger = FakeDownloadLedgerStore()
        // 统计的唯一出口是 module 快照（#7）：设置页读 library.state.stats。
        val library = downloadLibraryFor(mediaDownloads, ledger)
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track)),
            mediaDownloads = mediaDownloads, downloadLedgerStore = ledger, downloadLibrary = library
        )
        advanceUntilIdle()

        viewModel.loadCacheStats()
        advanceUntilIdle()

        assertEquals(1, library.state.value.stats.completeCount)
        assertEquals(36_000_000L, library.state.value.stats.usedBytes)
    }

    // ── 播放历史（工单 02：发现页个人化聚合的数据基础） ──────────────────

    @Test
    fun `最近播放与最常播放各自加载一次，重复调用不再请求`() = runTest {
        val recent = Track("recent-1", "刚听的", "歌手", "专辑", 1L, null)
        val frequent = Track("frequent-1", "常听的", "歌手", "专辑", 1L, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(recent))
            mostPlayedResult = Result.success(listOf(frequent))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()

        assertEquals(listOf(recent), viewModel.state.value.recentlyPlayed)
        assertEquals(listOf(frequent), viewModel.state.value.mostPlayed)
        // 幂等：字段非空即已加载，切回发现页不重发
        assertEquals(1, api.recentlyPlayedCalls)
        assertEquals(1, api.mostPlayedCalls)
    }

    @Test
    fun `最近添加加载专辑列表`() = runTest {
        val album = Album("album-1", "新专辑", "歌手", 10, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyAddedResult = Result.success(listOf(album))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyAdded()
        advanceUntilIdle()

        assertEquals(listOf(album), viewModel.state.value.recentlyAdded)
    }

    @Test
    fun `单个播放历史查询失败不影响其余 section`() = runTest {
        val frequent = Track("frequent-1", "常听的", "歌手", "专辑", 1L, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.failure(JellyfinError.Http(500))
            mostPlayedResult = Result.success(listOf(frequent))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()

        // 失败的一节保持 null（界面隐藏该 section），另一节照常显示
        assertNull(viewModel.state.value.recentlyPlayed)
        assertEquals(listOf(frequent), viewModel.state.value.mostPlayed)
        // 局部失败不升级成整页错误，也不把用户踢回登录
        assertNull(viewModel.state.value.errorMessage)
    }

    @Test
    fun `播放历史请求失败后下次进页会重试，不被幂等守卫永久挡住`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.failure(JellyfinError.Http(500))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        advanceUntilIdle()
        assertNull(viewModel.state.value.recentlyPlayed)

        // 服务恢复：失败不能写成「已加载且为空」，否则这一节到进程结束都不再请求
        api.recentlyPlayedResult = Result.success(listOf(track))
        viewModel.loadRecentlyPlayed()
        advanceUntilIdle()

        assertEquals(2, api.recentlyPlayedCalls)
        assertEquals(listOf(track), viewModel.state.value.recentlyPlayed)
    }

    @Test
    fun `播放历史断网时进离线态而非伪装成没数据`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.failure(JellyfinError.Network(IOException("no network")))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        advanceBounded()

        assertTrue(viewModel.state.value.isOffline)
        assertNull(viewModel.state.value.recentlyPlayed)
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `最近添加同样幂等且失败可重试`() = runTest {
        val album = Album("album-1", "新专辑", "歌手", 10, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyAddedResult = Result.failure(JellyfinError.Http(500))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyAdded()
        advanceUntilIdle()
        assertNull(viewModel.state.value.recentlyAdded)

        api.recentlyAddedResult = Result.success(listOf(album))
        viewModel.loadRecentlyAdded()
        viewModel.loadRecentlyAdded()
        advanceUntilIdle()

        assertEquals(2, api.recentlyAddedCalls)
        assertEquals(listOf(album), viewModel.state.value.recentlyAdded)
    }

    @Test
    fun `播放历史查询撞上 401 走会话过期`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.failure(JellyfinError.Unauthorized())
        }
        val authorization = FakeAuthorizationSink("active-auth")
        val viewModel = createViewModel(sessions, api, authorization)
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        advanceUntilIdle()

        assertTrue(sessions.cleared)
        assertNull(authorization.value)
        assertEquals("登录已失效，请重新登录", viewModel.state.value.errorMessage)
    }

    @Test
    fun `播放历史过期响应不写入新会话状态`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stale = Track("stale-1", "上个账号的", "歌手", "专辑", 1L, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            historyGate = gate
            recentlyPlayedResult = Result.success(listOf(stale))
        }
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        advanceTimeBy(1)
        // 请求还没回来就切会话：旧响应必须作废
        viewModel.signOut()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.recentlyPlayed)
    }

    @Test
    fun `换账号重新登录清空播放历史，不残留上个账号的数据`() = runTest {
        val recent = Track("recent-1", "刚听的", "歌手", "专辑", 1L, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(recent))
            mostPlayedResult = Result.success(listOf(recent))
            recentlyAddedResult = Result.success(listOf(Album("album-1", "新专辑", "歌手", 10, null)))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        viewModel.loadRecentlyAdded()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.recentlyPlayed != null)

        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-2", "token-2"))
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user-2")
        viewModel.updatePassword("pass-2")
        viewModel.login()
        advanceUntilIdle()

        assertNull(viewModel.state.value.recentlyPlayed)
        assertNull(viewModel.state.value.mostPlayed)
        assertNull(viewModel.state.value.recentlyAdded)
    }

    @Test
    fun `发现页下拉刷新重拉随机专辑与三节历史`() = runTest {
        val recent = Track("recent-1", "刚听的", "歌手", "专辑", 1L, null)
        val frequent = Track("frequent-1", "常听的", "歌手", "专辑", 1L, null)
        val added = Album("album-1", "新专辑", "歌手", 10, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(recent))
            mostPlayedResult = Result.success(listOf(frequent))
            recentlyAddedResult = Result.success(listOf(added))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadDiscoverAlbums()
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        viewModel.loadRecentlyAdded()
        advanceUntilIdle()
        assertEquals(1, api.recentlyPlayedCalls)
        assertEquals(1, api.randomAlbumsCalls)

        viewModel.refreshDiscover()
        advanceUntilIdle()

        // 清字段放行幂等守卫：三节都真的重新请求了
        assertEquals(2, api.recentlyPlayedCalls)
        assertEquals(2, api.mostPlayedCalls)
        assertEquals(2, api.recentlyAddedCalls)
        // 随机专辑也一起重拉（它本来就不幂等，这里锁住接线）
        assertEquals(2, api.randomAlbumsCalls)
        assertEquals(listOf(recent), viewModel.state.value.recentlyPlayed)
        assertEquals(listOf(frequent), viewModel.state.value.mostPlayed)
        assertEquals(listOf(added), viewModel.state.value.recentlyAdded)
    }

    @Test
    fun `播放历史请求失败时只标记失败的那一节，其余照常加载`() = runTest {
        val frequent = Track("frequent-1", "常听的", "歌手", "专辑", 1L, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.failure(JellyfinError.Http(500))
            mostPlayedResult = Result.success(listOf(frequent))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()

        // 失败只影响最近播放：界面只对那一节不再显示骨架，最常播放照常显示
        assertEquals(setOf(LibrarySectionKey.RecentlyPlayed), viewModel.state.value.discoverFailedSections)
        assertEquals(listOf(frequent), viewModel.state.value.mostPlayed)
    }

    @Test
    fun `重新发起加载会清掉该节的失败标记，重试能看到骨架`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.failure(JellyfinError.Http(500))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        advanceUntilIdle()
        assertTrue(LibrarySectionKey.RecentlyPlayed in viewModel.state.value.discoverFailedSections)

        // 失败字段保持 null，所以幂等守卫放行；再次加载先清标记再请求
        api.recentlyPlayedResult = Result.success(listOf(track))
        viewModel.loadRecentlyPlayed()
        assertFalse(LibrarySectionKey.RecentlyPlayed in viewModel.state.value.discoverFailedSections)
        advanceUntilIdle()

        assertEquals(listOf(track), viewModel.state.value.recentlyPlayed)
    }

    @Test
    fun `切歌后播放历史标记过期但保留旧数据，重拉成功前不闪空`() = runTest {
        // 工单 09：播放上报会改服务端的 UserData，「最近播放」不能一直用旧缓存。
        // 但清成 null 会让发现页两节先闪成骨架——只打过期标记，旧列表留在屏幕上。
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(track))
            mostPlayedResult = Result.success(listOf(track))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()

        viewModel.onMediaItemTransition(
            MediaItem.Builder().setMediaId("track-1").build()
        )
        advanceUntilIdle()

        // 旧数据还在（界面不会先空一帧），只是版本号推进了
        assertEquals(listOf(track), viewModel.state.value.recentlyPlayed)
        assertEquals(listOf(track), viewModel.state.value.mostPlayed)
        assertEquals(1, viewModel.state.value.historyEpoch)

        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()
        assertEquals(2, api.recentlyPlayedCalls)
        assertEquals(2, api.mostPlayedCalls)
        // 版本号对上了：再调一次不会重复请求
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()
        assertEquals(2, api.recentlyPlayedCalls)
        assertEquals(2, api.mostPlayedCalls)
    }

    @Test
    fun `切歌后的重拉在途时旧历史仍然显示`() = runTest {
        val old = Track("old-1", "旧的", "歌手", "专辑", 1L, null)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(old))
            mostPlayedResult = Result.success(listOf(old))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()

        api.historyGate = gate
        viewModel.onMediaItemTransition(MediaItem.Builder().setMediaId("track-1").build())
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        advanceUntilIdle()

        // 请求还没回来：旧列表必须还在（这就是「不闪」）
        assertEquals(listOf(old), viewModel.state.value.recentlyPlayed)
        assertEquals(listOf(old), viewModel.state.value.mostPlayed)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, api.recentlyPlayedCalls)
    }

    @Test
    fun `下拉刷新期间历史旧数据保留，不闪骨架`() = runTest {
        val old = Track("old-1", "旧的", "歌手", "专辑", 1L, null)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(old))
            mostPlayedResult = Result.success(listOf(old))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.loadMostPlayed()
        viewModel.loadRecentlyAdded()
        advanceUntilIdle()

        api.historyGate = gate
        viewModel.refreshDiscover()
        advanceUntilIdle()

        // 刷新进行中：旧数据都在，界面不会掉进骨架屏
        assertTrue(viewModel.state.value.isDiscoverRefreshing)
        assertEquals(listOf(old), viewModel.state.value.recentlyPlayed)
        assertEquals(listOf(old), viewModel.state.value.mostPlayed)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isDiscoverRefreshing)
        assertEquals(2, api.recentlyPlayedCalls)
    }

    @Test
    fun `下拉刷新期间刷新标记为真，全部落地后复位`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.refreshDiscover()
        assertTrue(viewModel.state.value.isDiscoverRefreshing)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isDiscoverRefreshing)
    }

    @Test
    fun `退出登录清空播放历史，不残留上个账号的数据`() = runTest {
        val recent = Track("recent-1", "刚听的", "歌手", "专辑", 1L, null)
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(recent))
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        advanceUntilIdle()
        assertEquals(listOf(recent), viewModel.state.value.recentlyPlayed)

        viewModel.signOut()
        advanceUntilIdle()

        assertNull(viewModel.state.value.recentlyPlayed)
    }

    @Test
    fun `会话过期清空播放历史`() = runTest {
        val sessions = FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret"))
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(track))
            albumsError = JellyfinError.Unauthorized()
        }
        val viewModel = createViewModel(sessions, api)
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.recentlyPlayed != null)

        // 曲库请求撞上 401 → 会话过期，历史一并清空
        viewModel.loadAlbums()
        advanceUntilIdle()

        assertNull(viewModel.state.value.recentlyPlayed)
        assertNull(viewModel.state.value.mostPlayed)
        assertNull(viewModel.state.value.recentlyAdded)
    }

    // ===== 曲库加载 module 接线（#12）：界面只报「进了哪一节」 =====

    @Test
    fun `恢复会话就绪前进入分区静默跳过，就绪后自动补跑`() = runTest {
        val albums = listOf(Album("album-1", "专辑", "歌手", 1, null))
        val gate = CompletableDeferred<Unit>()
        val sessions = FakeSessionRepository(
            SavedSession("https://server.test", "user-1", "user", "secret")
        ).apply { loadGate = gate }
        val api = FakeJellyfinApi(albums = albums)
        val viewModel = createViewModel(sessions, api)

        // 恢复还没完成（本地会话还没读出来）：主页面已经组合并报过「进了专辑页」，静默无操作
        viewModel.onSectionEntered(LibrarySection.Albums)
        assertEquals(0, api.albumsCalls)
        assertNull(viewModel.state.value.albums)
        assertFalse(viewModel.state.value.isLoading)

        // 恢复完成 → 会话就绪 → 按记住的分区补跑，不用等用户再切一次页签
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(albums, viewModel.state.value.albums)
        assertEquals(1, api.albumsCalls)
    }

    @Test
    fun `退出登录后不再用上一个会话的分区补跑`() = runTest {
        val album = Album("album-1", "专辑", "歌手", 1, null)
        val api = FakeJellyfinApi(albums = listOf(album))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()
        viewModel.onSectionEntered(LibrarySection.AlbumDetail(album))
        advanceUntilIdle()
        assertEquals(album, viewModel.state.value.selectedAlbum)

        viewModel.signOut()
        advanceUntilIdle()

        // 重新登录：登录页与主页面是二选一组合，主页面重建时界面会重新上报分区。
        // 上一个会话的详情页**不能**被重放（旧专辑 id 的请求 + 旧数据写进新会话）。
        api.loginResult = Result.success(
            com.example.pulsar.data.jellyfin.JellyfinSession("user-2", "token-2")
        )
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user-2")
        viewModel.updatePassword("pass-2")
        viewModel.login()
        advanceUntilIdle()

        assertNull(viewModel.state.value.selectedAlbum)
        assertNull(viewModel.state.value.albumTracks)
    }

    @Test
    fun `专辑在途时进播放列表，专辑请求不会被取消`() = runTest {
        val album = Album("album-1", "专辑", "歌手", 1, null)
        val gate = CompletableDeferred<Unit>()
        val api = FakeJellyfinApi(albums = listOf(album)).apply { albumsGate = gate }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")), api
        )
        advanceUntilIdle()

        viewModel.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        viewModel.onSectionEntered(LibrarySection.Playlists)
        advanceUntilIdle()

        // 改前两者共用一个 albumsJob：进播放列表会把专辑请求取消，专辑页永远停在骨架
        assertEquals(emptyList<Playlist>(), viewModel.state.value.playlists)
        assertNull(viewModel.state.value.albums)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(album), viewModel.state.value.albums)
    }

    // ── 工单 13：登出 / 过期 / 回在线的清理语义收在 slice 上 ────────────────

    @Test
    fun `退出登录把三个 slice 清回默认，地址历史保留`() = runTest {
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(track))
        }
        val history = FakeServerUrlHistory()
        val viewModel = createViewModel(FakeSessionRepository(null), api, serverUrlHistory = history)
        advanceUntilIdle()
        api.loginResult = Result.success(com.example.pulsar.data.jellyfin.JellyfinSession("user-1", "token-1"))
        viewModel.updateServerUrl("https://server.test")
        viewModel.updateUsername("user")
        viewModel.updatePassword("pass")
        viewModel.login()
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.enterMultiSelect(track.id)
        advanceUntilIdle()
        assertEquals(listOf(track), viewModel.state.value.recentlyPlayed)
        assertEquals(setOf(track.id), viewModel.state.value.multiSelectIds)

        viewModel.signOut()
        advanceUntilIdle()

        // 曲库与下载界面字段一起清（新增字段由 slice 的 cleared() 自动跟上）
        assertEquals(LibraryState(), viewModel.state.value.library)
        assertEquals(DownloadsState(), viewModel.state.value.downloads)
        // 登出只清会话与表单；地址历史是本地数据，留着
        assertEquals("", viewModel.state.value.serverUrl)
        assertEquals("", viewModel.state.value.username)
        assertEquals(listOf("https://server.test"), viewModel.state.value.serverUrlHistory)
    }

    @Test
    fun `会话过期与登出清同一份状态，连版本号与回收站一起清`() = runTest {
        val recycleFile = LibraryRecycleFile(relPath = "a.flac", size = 1)
        val fileOps = FakeLibraryFilesApi(
            recycleResult = Result.success(LibraryRecycleResponse(files = listOf(recycleFile)))
        )
        val api = FakeJellyfinApi(tracks = listOf(track)).apply {
            recentlyPlayedResult = Result.success(listOf(track))
            albumsError = JellyfinError.Unauthorized()
        }
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api,
            libraryFilesFactory = { fileOps }
        )
        advanceUntilIdle()
        viewModel.loadRecentlyPlayed()
        viewModel.loadRecycle()
        advanceUntilIdle()
        viewModel.onMediaItemTransition(MediaItem.Builder().setMediaId(track.id).build())
        // 改前过期不清这两个：版本号留着、回收站列表留着——同一个「会话结束」却清法不同
        assertEquals(1, viewModel.state.value.historyEpoch)
        assertEquals(listOf(recycleFile), viewModel.state.value.recycleFiles)

        // 401 → 会话过期
        viewModel.loadAlbums()
        advanceUntilIdle()

        assertEquals("登录已失效，请重新登录", viewModel.state.value.errorMessage)
        assertEquals(LibraryState(), viewModel.state.value.library)
        assertEquals(DownloadsState(), viewModel.state.value.downloads)
        // 过期保留表单（用户只需重填密码），地址历史不动
        assertEquals("https://server.test", viewModel.state.value.serverUrl)
        assertEquals("user", viewModel.state.value.username)
        assertEquals("", viewModel.state.value.password)
    }

    private fun TestScope.createViewModelWithFileOps(
        fileOps: FakeLibraryFilesApi,
        tracks: List<Track>,
        mediaDownloads: FakeMediaDownloads? = null
    ): JellyfinViewModel {
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = tracks),
            libraryFilesFactory = { fileOps },
            mediaDownloads = mediaDownloads
        )
        advanceUntilIdle()
        return viewModel
    }

    /** 真 module + Fake 字节/名单：验证 ViewModel 接线，不复制交集逻辑。 */
    private fun TestScope.downloadLibraryFor(
        downloads: FakeMediaDownloads,
        ledger: DownloadLedgerStore?
    ): DownloadLibrary = DownloadLibrary(
        downloads = downloads,
        ledger = ledger,
        // 名单为 null = 测试「只看字节真相」的兜底；scheduler 仍要一个可写的 store。
        scheduler = DownloadScheduler(backgroundScope, downloads, ledger ?: FakeDownloadLedgerStore()),
        scope = backgroundScope
    )

    /** 删除链路一律走真 module：生产路径不再有 ViewModel 里的第二份删除实现。 */
    private fun TestScope.createDeleteTestViewModel(
        api: FakeJellyfinApi,
        mediaDownloads: FakeMediaDownloads,
        ledger: FakeDownloadLedgerStore
    ): JellyfinViewModel = createViewModel(
        FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
        api,
        mediaDownloads = mediaDownloads,
        downloadLedgerStore = ledger,
        downloadLibrary = downloadLibraryFor(mediaDownloads, ledger)
    )

    private fun TestScope.createViewModel(
        sessions: FakeSessionRepository,
        api: FakeJellyfinApi,
        authorization: FakeAuthorizationSink = FakeAuthorizationSink(),
        libraryFilesFactory: (suspend () -> LibraryFilesApi)? = null,
        mediaDownloads: FakeMediaDownloads? = null,
        snapshotRepository: FakeSnapshotRepository? = null,
        serverUrlHistory: FakeServerUrlHistory? = null,
        downloadLedgerStore: FakeDownloadLedgerStore? = null,
        downloadLibrary: DownloadLibrary? = null
    ): JellyfinViewModel {
        // 生产路径只有一条：有下载能力就一定接真 module（旧 ViewModel 派生已在 #7 删除）。
        val library = downloadLibrary
            ?: mediaDownloads?.let { downloadLibraryFor(it, downloadLedgerStore) }
        return JellyfinViewModel(
            sessions, FakeApiFactory(api), authorization, StandardTestDispatcher(testScheduler),
            libraryFilesFactory, mediaDownloads, snapshotRepository, serverUrlHistory,
            downloadLedgerStore, library
        )
    }

    /** 下载名单的内存 Fake（迁移测试用）。 */
    private class FakeDownloadLedgerStore : DownloadLedgerStore {
        var entries = emptyList<DownloadEntry>()
        var replaceCalls = 0
        var done = false
        /** 写盘失败：模拟 DataStore 坏了，迁移必须静默放弃。 */
        var failWrite = false
        override suspend fun load() = DownloadLedger(entries)
        override suspend fun replaceAll(entries: List<DownloadEntry>) {
            if (failWrite) throw RuntimeException("ledger broken")
            replaceCalls++
            this.entries = entries
        }
        override suspend fun entriesFor(serverUrl: String, userId: String) =
            entries.filter { it.serverUrl == serverUrl && it.userId == userId }

        override suspend fun pruneUnattributed(): Int = 0

        override suspend fun update(block: suspend (List<DownloadEntry>) -> List<DownloadEntry>) {
            if (failWrite) throw RuntimeException("ledger broken")
            replaceCalls++
            this.entries = block(this.entries)
        }
    }

    /** 地址历史的内存 Fake：记录写入与读取，验证登录成功时的 record 调用。 */
    private class FakeServerUrlHistory : ServerUrlHistory {
        val recorded = mutableListOf<String>()
        private val urls = mutableListOf<String>()
        /**
         * 非空时 [load] 会真的挂起，直到测试放行。真 DataStore 读盘本来就是挂起的，
         * 这里必须能复现那个挂起点，否则「跨挂起点的读-改-写」竞态在测试里根本不出现。
         */
        var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun load(): List<String> {
            loadGate?.await()
            return urls.toList()
        }
        override suspend fun record(url: String) {
            recorded += url
            urls.remove(url)
            urls.add(0, url)
        }
    }

    private class FakeMediaDownloads : MediaDownloads {
        val completeKeys = mutableSetOf<String>()
        val removedKeys = mutableListOf<String>()
        var failAll = false

        /** 非空时 [remove] 会真的挂起直到放行：复现「删到一半会话失效」的竞态。 */
        var removeGate: CompletableDeferred<Unit>? = null

        override suspend fun hasCompleteCopy(key: String): Boolean {
            if (failAll) throw RuntimeException("downloads broken")
            return key in completeKeys
        }
        override suspend fun usedBytes(): Long =
            if (failAll) throw RuntimeException("downloads broken") else 36_000_000L
        override suspend fun completeCount(): Int =
            if (failAll) throw RuntimeException("downloads broken") else completeKeys.size
        override suspend fun completeKeys(): Set<String> {
            if (failAll) throw RuntimeException("downloads broken")
            return completeKeys.toSet()
        }
        override suspend fun download(
            url: String,
            key: String,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        ): Boolean {
            if (failAll) return false
            completeKeys += key
            onProgress(1L, 1L)
            return true
        }
        override suspend fun remove(key: String) {
            removeGate?.await()
            removedKeys += key
            completeKeys -= key
        }
    }

    private class FakeSnapshotRepository : LibrarySnapshotRepository {
        var stored: LibrarySnapshot? = null
        var saved = 0
        var cleared = false
        var failLoad = false
        override suspend fun load(): LibrarySnapshot? {
            if (failLoad) throw RuntimeException("snapshot unreadable")
            return stored
        }
        override suspend fun save(snapshot: LibrarySnapshot) { saved++; stored = snapshot }
        override suspend fun clear() { cleared = true; stored = null }
    }

    private class FakeSessionRepository(private var session: SavedSession?) : SessionRepository {
        var cleared = false
        /** 非空时 [load] 真的挂起：复现「会话恢复还没写完，别的协程已经在读状态」。 */
        var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun load(): SavedSession? {
            loadGate?.await()
            return session
        }
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
        private val albums: List<Album> = emptyList(),
        var musicItemsError: Throwable? = null,
        var albumsError: Throwable? = null,
        private val searchResults: SearchResults = SearchResults(),
        var lyricsResult: Result<Lyrics>? = null
    ) : JellyfinApi {
        var musicItemsCalls = 0
        var searchError: Throwable? = null
        val searchQueries = mutableListOf<String>()
        var lyricsCalls = 0
        val lyricsTrackIds = mutableListOf<String>()
        /** 播放历史（工单 02）：默认成功返回空列表，各用例按需注入。 */
        var recentlyPlayedResult: Result<List<Track>> = Result.success(emptyList())
        var mostPlayedResult: Result<List<Track>> = Result.success(emptyList())
        var recentlyAddedResult: Result<List<Album>> = Result.success(emptyList())
        var recentlyPlayedCalls = 0
        var mostPlayedCalls = 0
        var recentlyAddedCalls = 0
        /** 挂起播放历史请求，模拟「响应回来前会话已切换」。 */
        var historyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun getRecentlyPlayed(userId: String, accessToken: String, limit: Int): Result<List<Track>> {
            recentlyPlayedCalls++
            historyGate?.await()
            return recentlyPlayedResult
        }
        override suspend fun getMostPlayed(userId: String, accessToken: String, limit: Int): Result<List<Track>> {
            mostPlayedCalls++
            historyGate?.await()
            return mostPlayedResult
        }
        override suspend fun getRecentlyAdded(userId: String, accessToken: String, limit: Int): Result<List<Album>> {
            recentlyAddedCalls++
            historyGate?.await()
            return recentlyAddedResult
        }
        /** 登录结果：默认失败；测「换账号重登」时注入成功。 */
        var loginResult: Result<com.example.pulsar.data.jellyfin.JellyfinSession>? = null
        override suspend fun login(username: String, password: String) =
            loginResult ?: Result.failure<com.example.pulsar.data.jellyfin.JellyfinSession>(IllegalStateException())
        override suspend fun getMusicItems(userId: String, accessToken: String): Result<List<Track>> {
            musicItemsCalls++
            return musicItemsError?.let { Result.failure(it) } ?: Result.success(tracks)
        }
        var albumsCalls = 0
        /** 非空时 getAlbums 会挂起到测试放行：复现「专辑请求在途时用户切了页」。 */
        var albumsGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun getAlbums(userId: String, accessToken: String): Result<List<Album>> {
            albumsCalls++
            albumsGate?.await()
            return albumsError?.let { Result.failure(it) } ?: Result.success(albums)
        }
        var randomAlbumsCalls = 0
        override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int): Result<List<Album>> {
            randomAlbumsCalls++
            return Result.success(emptyList())
        }
        override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String) = Result.success(emptyList<Track>())
        var playlistsError: Throwable? = null
        /** 播放列表详情返回的曲目（工单 08：在线打开列表详情会写已下载列表清单）。 */
        var playlistTracksResult: Result<List<Track>> = Result.success(emptyList())
        override suspend fun getPlaylists(userId: String, accessToken: String): Result<List<Playlist>> =
            playlistsError?.let { Result.failure(it) } ?: Result.success(emptyList())
        override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String) =
            playlistTracksResult
        override suspend fun search(userId: String, accessToken: String, query: String): Result<SearchResults> {
            searchQueries += query
            return searchError?.let { Result.failure(it) } ?: Result.success(searchResults)
        }
        override suspend fun getLyrics(accessToken: String, trackId: String): Result<Lyrics> {
            lyricsCalls++
            lyricsTrackIds += trackId
            return lyricsResult ?: Result.success(Lyrics.NONE)
        }
        // 缓存键 = streamUrl，Fake 按 id 生成唯一 URL，两首歌才是两个不同的键
        override fun streamUrl(mediaId: String, accessToken: String) = "stream/$mediaId"
        override suspend fun reportPlaybackStarted(accessToken: String, itemId: String, positionTicks: Long) = Result.success(Unit)
        override suspend fun reportPlaybackStopped(accessToken: String, itemId: String, positionTicks: Long) = Result.success(Unit)
        override fun imageRequest(itemId: String, imageTag: String?, accessToken: String) = JellyfinRequest("image", emptyMap())
        override fun authorizationHeader(accessToken: String) = "auth-header"
    }

    private class FakeLibraryFilesApi(
        var deleteResult: Result<LibraryDeleteResponse> = Result.success(LibraryDeleteResponse(deleted = listOf("x.flac"))),
        var renameResult: Result<LibraryRenameResponse> = Result.success(LibraryRenameResponse(ok = true)),
        var recycleResult: Result<LibraryRecycleResponse> = Result.success(LibraryRecycleResponse())
    ) : LibraryFilesApi {
        var deleteCalls = 0
        val deletedPaths = mutableListOf<List<String>>()
        var renameCalls = 0
        var renameTitle: String? = null
        var renameArtist: String? = null
        var renameAlbum: String? = null
        val restoredPaths = mutableListOf<List<String>>()
        var purgeCalls = 0
        override suspend fun delete(paths: List<String>): Result<LibraryDeleteResponse> {
            deleteCalls++
            deletedPaths += paths
            return deleteResult
        }
        override suspend fun rename(
            path: String,
            title: String?,
            artist: String?,
            album: String?
        ): Result<LibraryRenameResponse> {
            renameCalls++
            renameTitle = title
            renameArtist = artist
            renameAlbum = album
            return renameResult
        }
        override suspend fun recycle(): Result<LibraryRecycleResponse> = recycleResult
        override suspend fun restore(paths: List<String>): Result<LibraryRestoreResponse> {
            restoredPaths += paths
            return Result.success(LibraryRestoreResponse(restored = paths))
        }
        override suspend fun purge(): Result<LibraryPurgeResponse> {
            purgeCalls++
            return Result.success(LibraryPurgeResponse(purged = 1))
        }
    }
}
