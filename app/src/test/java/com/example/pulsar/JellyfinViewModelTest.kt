package com.example.pulsar

import androidx.lifecycle.viewModelScope
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
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    // ── 本地缓存与离线（工单 01/02/03/04/05/06）─────────────────────
    // 断网场景有两个注意点（都踩过）：
    // ① 别用 advanceUntilIdle——探活协程会往调度器里排 delay，推虚拟时间会把它
    //   每轮跑完又排下一轮，无限喂队列（QQDownloadViewModelTest 的同款教训）；
    //   一律用有界 advanceBounded。
    // ② 测试体收尾要调 stopOfflineForTest——viewModelScope 不归 runTest 管，
    //   留着探活协程 teardown 要白推 500 轮 delay。

    /** 断网场景的有界推进：跑完当前排队任务即可，不碰探活的 delay 队列。 */
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
        val mediaCache = FakeMediaCache()
        // 缓存键 = streamUrl(mediaId)；Fake 按 id 生成 "stream/{id}"
        mediaCache.completeKeys += "stream/cached-1"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaCache = mediaCache, snapshotRepository = snapshotRepo
        )
        advanceBounded()

        // cachedTrackIds 计算完成后：有缓存那首可播，没缓存那首不可播
        advanceBounded()
        assertTrue(viewModel.isPlayable(cached))
        assertTrue(!viewModel.isPlayable(uncached))
        assertEquals("这首歌没有缓存，联网后才能播放", viewModel.unplayableReason(uncached))
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
        val mediaCache = FakeMediaCache()
        mediaCache.failAll = true
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaCache = mediaCache, snapshotRepository = snapshotRepo
        )
        advanceBounded()
        // 判定失败 = 没有缓存能力，全部按不可播处理——不是崩溃
        assertTrue(!viewModel.isPlayable(track))
        viewModel.stopOfflineForTest()
    }

    @Test
    fun `断网时搜索改为快照过滤并标记离线搜索`() = runTest {
        val api = FakeJellyfinApi()
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
    fun `退出登录清空缓存与快照（换账号不串数据）`() = runTest {
        val mediaCache = FakeMediaCache()
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track)),
            mediaCache = mediaCache, snapshotRepository = snapshotRepo
        )
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        assertTrue(mediaCache.cleared)
        assertTrue(snapshotRepo.cleared)
    }

    @Test
    fun `会话过期（token 失效）不清缓存也不清快照`() = runTest {
        val api = FakeJellyfinApi()
        api.musicItemsError = JellyfinError.Unauthorized()
        val mediaCache = FakeMediaCache()
        val snapshotRepo = FakeSnapshotRepository()
        snapshotRepo.stored = LibrarySnapshot(tracks = listOf(track))
        createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaCache = mediaCache, snapshotRepository = snapshotRepo
        )
        // 401 → 过期分支（无网络失败，没有常驻探活协程），可以安全推到底
        advanceUntilIdle()

        assertTrue(!mediaCache.cleared)
        assertTrue(!snapshotRepo.cleared)
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
    fun `清空缓存保留正在播的那首，不被从播放流底下抽走`() = runTest {
        val mediaCache = FakeMediaCache()
        mediaCache.completeKeys += "stream/track-1"
        mediaCache.completeKeys += "stream/other"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track)),
            mediaCache = mediaCache
        )
        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(track), "track-1")

        viewModel.clearCache()
        advanceUntilIdle()

        // 正在播的 "stream/track-1" 留下，其余副本全清
        assertEquals(setOf("stream/track-1"), mediaCache.completeKeys)
        assertEquals(setOf("stream/track-1"), mediaCache.lastClearKept)
        assertTrue(mediaCache.removedKeys.contains("stream/other"))
    }

    @Test
    fun `已缓存页按专辑列出完整副本的进度`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
        val t3 = Track("t3", "Song B1", "Artist Y", "Album B", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 2, null)
        val albumB = Album("a2", "Album B", "Artist Y", 1, null)
        val mediaCache = FakeMediaCache()
        mediaCache.completeKeys += "stream/t1"
        mediaCache.completeKeys += "stream/t3"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(t1, t2, t3), albums = listOf(albumA, albumB)),
            mediaCache = mediaCache
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()

        viewModel.loadCachedAlbums()
        advanceUntilIdle()

        assertEquals(
            listOf(
                CachedAlbum(albumA, cachedCount = 1, totalCount = 2),
                CachedAlbum(albumB, cachedCount = 1, totalCount = 1)
            ),
            viewModel.state.value.cachedAlbums
        )
    }

    @Test
    fun `按专辑删副本：正在播的保留，其余删掉，统计与列表同步刷新`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t2 = Track("t2", "Song A2", "Artist X", "Album A", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 2, null)
        val mediaCache = FakeMediaCache()
        mediaCache.completeKeys += "stream/t1"
        mediaCache.completeKeys += "stream/t2"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(t1, t2), albums = listOf(albumA)),
            mediaCache = mediaCache
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()
        viewModel.setPlaybackQueue(listOf(t1, t2), "t1")

        viewModel.deleteAlbumCopies(albumA)
        advanceUntilIdle()

        assertEquals(setOf("stream/t1"), mediaCache.completeKeys)
        assertTrue(mediaCache.removedKeys.contains("stream/t2"))
        assertEquals(1, viewModel.state.value.cacheTrackCount)
        assertEquals(listOf(CachedAlbum(albumA, cachedCount = 1, totalCount = 2)), viewModel.state.value.cachedAlbums)
    }

    @Test
    fun `整张专辑删光后从已缓存列表消失`() = runTest {
        val t1 = Track("t1", "Song A1", "Artist X", "Album A", 1000L, null)
        val t3 = Track("t3", "Song B1", "Artist Y", "Album B", 1000L, null)
        val albumA = Album("a1", "Album A", "Artist X", 1, null)
        val albumB = Album("a2", "Album B", "Artist Y", 1, null)
        val mediaCache = FakeMediaCache()
        mediaCache.completeKeys += "stream/t1"
        mediaCache.completeKeys += "stream/t3"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(t1, t3), albums = listOf(albumA, albumB)),
            mediaCache = mediaCache
        )
        advanceUntilIdle()
        viewModel.loadAlbums()
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()

        viewModel.deleteAlbumCopies(albumB)
        advanceUntilIdle()

        assertTrue(mediaCache.removedKeys.contains("stream/t3"))
        assertEquals(listOf(CachedAlbum(albumA, cachedCount = 1, totalCount = 1)), viewModel.state.value.cachedAlbums)
    }

    @Test
    fun `换账号重新登录后已缓存列表清空，不残留旧账号的分组`() = runTest {
        val mediaCache = FakeMediaCache()
        mediaCache.completeKeys += "stream/track-1"
        val api = FakeJellyfinApi(tracks = listOf(track))
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            api, mediaCache = mediaCache
        )
        advanceUntilIdle()
        viewModel.loadCachedAlbums()
        advanceUntilIdle()
        assertEquals(emptyList<CachedAlbum>(), viewModel.state.value.cachedAlbums)

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
        val mediaCache = FakeMediaCache()
        val fileOps = FakeLibraryFilesApi()
        val deletable = track.copy(id = "t1", filePath = "E:\\music\\t1.flac")
        val viewModel = createViewModelWithFileOps(
            fileOps, tracks = listOf(deletable),
            mediaCache = mediaCache
        )

        viewModel.deleteTrack(deletable)

        advanceUntilIdle()
        assertTrue(viewModel.state.value.tracks.isNullOrEmpty())
        assertEquals(listOf("stream/t1"), mediaCache.removedKeys)
    }

    @Test
    fun `改名成功不动缓存（副本照样能放）`() = runTest {
        val mediaCache = FakeMediaCache()
        val fileOps = FakeLibraryFilesApi()
        val renameable = track.copy(id = "t1", filePath = "E:\\music\\t1.flac")
        val viewModel = createViewModelWithFileOps(
            fileOps, tracks = listOf(renameable),
            mediaCache = mediaCache
        )

        viewModel.renameTrack(renameable, title = "新名字")

        advanceUntilIdle()
        // 工单 06：改名改的是内嵌标签，歌没变，副本一个字节都不动
        assertTrue(mediaCache.removedKeys.isEmpty())
        assertTrue(!mediaCache.cleared)
    }

    @Test
    fun `清空缓存后占用与首数归零并带完成提示`() = runTest {
        val mediaCache = FakeMediaCache()
        mediaCache.completeKeys += "stream/track-1"
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = listOf(track)),
            mediaCache = mediaCache
        )
        advanceUntilIdle()

        viewModel.loadCacheStats()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.cacheTrackCount)

        viewModel.clearCache()
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.cacheTrackCount)
        assertTrue(viewModel.state.value.cacheMessage != null)
        assertTrue(!viewModel.state.value.isCacheBusy)
    }

    private fun TestScope.createViewModelWithFileOps(
        fileOps: FakeLibraryFilesApi,
        tracks: List<Track>,
        mediaCache: FakeMediaCache? = null
    ): JellyfinViewModel {
        val viewModel = createViewModel(
            FakeSessionRepository(SavedSession("https://server.test", "user-1", "user", "secret")),
            FakeJellyfinApi(tracks = tracks),
            libraryFilesFactory = { fileOps },
            mediaCache = mediaCache
        )
        advanceUntilIdle()
        return viewModel
    }

    private fun TestScope.createViewModel(
        sessions: FakeSessionRepository,
        api: FakeJellyfinApi,
        authorization: FakeAuthorizationSink = FakeAuthorizationSink(),
        libraryFilesFactory: (suspend () -> LibraryFilesApi)? = null,
        mediaCache: FakeMediaCache? = null,
        snapshotRepository: FakeSnapshotRepository? = null
    ): JellyfinViewModel = JellyfinViewModel(
        sessions, FakeApiFactory(api), authorization, StandardTestDispatcher(testScheduler),
        libraryFilesFactory, mediaCache, snapshotRepository
    )

    private class FakeMediaCache : MediaCache {
        val completeKeys = mutableSetOf<String>()
        var cleared = false
        var lastClearKept: Set<String>? = null
        val removedKeys = mutableListOf<String>()
        var failAll = false
        override suspend fun hasCompleteCopy(key: String): Boolean {
            if (failAll) throw RuntimeException("cache broken")
            return key in completeKeys
        }
        override suspend fun usedBytes(): Long = if (failAll) throw RuntimeException("cache broken") else 36_000_000L
        override suspend fun completeCount(): Int = if (failAll) throw RuntimeException("cache broken") else completeKeys.size
        override suspend fun completeKeys(): Set<String> {
            if (failAll) throw RuntimeException("cache broken")
            return completeKeys.toSet()
        }
        override suspend fun clear(keepKeys: Set<String>) {
            cleared = true
            lastClearKept = keepKeys
            (completeKeys - keepKeys).forEach { removedKeys += it }
            completeKeys.clear()
            completeKeys += keepKeys
        }
        override suspend fun remove(key: String) { removedKeys += key; completeKeys -= key }
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
        override suspend fun load() = session
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
        /** 登录结果：默认失败；测「换账号重登」时注入成功。 */
        var loginResult: Result<com.example.pulsar.data.jellyfin.JellyfinSession>? = null
        override suspend fun login(username: String, password: String) =
            loginResult ?: Result.failure<com.example.pulsar.data.jellyfin.JellyfinSession>(IllegalStateException())
        override suspend fun getMusicItems(userId: String, accessToken: String): Result<List<Track>> {
            musicItemsCalls++
            return musicItemsError?.let { Result.failure(it) } ?: Result.success(tracks)
        }
        override suspend fun getAlbums(userId: String, accessToken: String): Result<List<Album>> =
            albumsError?.let { Result.failure(it) } ?: Result.success(albums)
        override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int) = Result.success(emptyList<Album>())
        override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String) = Result.success(emptyList<Track>())
        var playlistsError: Throwable? = null
        override suspend fun getPlaylists(userId: String, accessToken: String): Result<List<Playlist>> =
            playlistsError?.let { Result.failure(it) } ?: Result.success(emptyList())
        override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String) = Result.success(emptyList<Track>())
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
