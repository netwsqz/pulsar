package com.example.pulsar.library

import com.example.pulsar.JellyfinUiState
import com.example.pulsar.LibrarySection
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.jellyfin.JellyfinSession
import com.example.pulsar.data.jellyfin.Lyrics
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.state.LibrarySectionKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * 曲库加载 module 的单元测试（#12 / `docs/library-state-refactor.md` Step 2）。
 *
 * 钉死三件事：**每节独立的 job + 代次（跨节互不取消、同节取消旧请求）**、
 * **三态语义（null / 空列表 / 失败）**、**会话未就绪静默跳过**。
 * 宿主用 Fake：状态放在真实的 [JellyfinUiState] 里，接缝行为与 `JellyfinViewModel` 一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCacheTest {
    private val album = Album("album-1", "专辑一", "歌手", 10, null)
    private val otherAlbum = Album("album-2", "专辑二", "歌手", 20, null)
    private val track = Track("track-1", "歌", "歌手", "专辑一", 1000L, null)
    private val playlist = Playlist("playlist-1", "跑步", 3, null)

    // ── 跨节互不取消（这是 #12 有意修复的 bug） ──────────────────────

    @Test
    fun `专辑加载在途时进播放列表，专辑请求不会被取消`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.albumGates += gate
        api.albumResults += Result.success(listOf(album))
        api.playlistsResult = Result.success(listOf(playlist))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        cache.onSectionEntered(LibrarySection.Playlists)
        advanceUntilIdle()
        // 播放列表先落地，专辑还在途——旧实现这里会把专辑请求取消掉
        assertNull(host.state().library.albums)
        assertEquals(listOf(playlist), host.state().library.playlists)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(album), host.state().library.albums)
        assertEquals(1, api.albumCalls)
    }

    @Test
    fun `专辑-播放列表-专辑连续切换，三个请求都各自落地`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.success(listOf(album))
        api.albumResults += Result.success(listOf(otherAlbum))
        api.playlistsResult = Result.success(listOf(playlist))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        // 先进专辑（在途），再进播放列表（在途），再回专辑（取消并重发专辑那一节）
        val firstGate = CompletableDeferred<Unit>()
        api.albumGates += firstGate
        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        cache.onSectionEntered(LibrarySection.Playlists)
        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        firstGate.complete(Unit)
        advanceUntilIdle()

        // 播放列表没有被专辑的第二次进入取消
        assertEquals(listOf(playlist), host.state().library.playlists)
        // 专辑列表是最后一次请求的结果（旧响应因代次对不上被丢弃）
        assertEquals(listOf(otherAlbum), host.state().library.albums)
        assertEquals(2, api.albumCalls)
    }

    @Test
    fun `同一节重复进入会取消旧请求，不发两条都在跑的网络请求`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.albumGates += gate
        api.albumResults += Result.success(listOf(album))
        api.albumResults += Result.success(listOf(otherAlbum))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()

        assertEquals(2, api.albumCalls)
        // 第一次请求被取消：它的响应（即使回来）也不会写回
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(otherAlbum), host.state().library.albums)
    }

    // ── 会话未就绪：静默跳过 ────────────────────────────────────

    @Test
    fun `多节同时在途时，先落地的一节不会提前收起加载圈`() = runTest {
        val api = FakeApi()
        val albumsGate = CompletableDeferred<Unit>()
        val playlistsGate = CompletableDeferred<Unit>()
        api.albumGates += albumsGate
        api.playlistsGate = playlistsGate
        api.albumResults += Result.success(listOf(album))
        api.playlistsResult = Result.success(listOf(playlist))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        cache.onSectionEntered(LibrarySection.Playlists)
        advanceUntilIdle()
        assertTrue(host.state().session.isLoading)

        // 专辑先落地：播放列表还在途，加载圈不能收（否则播放列表页会闪一下「暂无」空态）
        albumsGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(host.state().session.isLoading)

        playlistsGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(host.state().session.isLoading)
    }

    @Test
    fun `刷新期间切歌替换了历史请求，刷新圈仍等新请求落地`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        host.currentSection = LibrarySection.Discover
        val cache = newCache(host)
        cache.onSectionEntered(LibrarySection.Discover)
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        api.historyGate = gate
        cache.refreshDiscover()
        advanceUntilIdle()
        assertTrue(host.state().library.isDiscoverRefreshing)

        // 切歌：三节历史的在途请求被替换成新的（同样挂着）——刷新圈不能跟着旧 job 提前收
        host.update { it.copy(library = it.library.copy(historyEpoch = it.library.historyEpoch + 1)) }
        cache.onHistoryEpochChanged()
        advanceUntilIdle()
        assertTrue(host.state().library.isDiscoverRefreshing)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(host.state().library.isDiscoverRefreshing)
    }

    @Test
    fun `会话未就绪时进入分区不请求、不置加载中、不报错`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection = null)
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()

        assertEquals(0, api.albumCalls)
        assertNull(host.state().library.albums)
        assertFalse(host.state().session.isLoading)
        assertNull(host.state().session.errorMessage)
    }

    @Test
    fun `会话就绪后补跑当前分区`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.success(listOf(album))
        val host = FakeHost(connection = null)
        val cache = newCache(host)
        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        assertEquals(0, api.albumCalls)

        // 会话就绪：ViewModel 接线 reloadCurrentSection() 补跑
        host.currentSection = LibrarySection.Albums
        host.connection = connection(api)
        host.requestReload()
        advanceUntilIdle()

        assertEquals(1, api.albumCalls)
        assertEquals(listOf(album), host.state().library.albums)
    }

    // ── 三态语义：null / 空列表 / 失败 ────────────────────────────

    @Test
    fun `加载失败保持 null 并给错误，不写成空列表`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.failure(JellyfinError.Http(500))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()

        assertNull(host.state().library.albums)
        assertEquals("服务器请求失败（500）", host.state().session.errorMessage)
        assertFalse(host.state().session.isLoading)
    }

    @Test
    fun `失败后下次进页会重试，不被幂等守卫挡住`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.failure(JellyfinError.Http(500))
        api.albumResults += Result.success(listOf(album))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        assertNull(host.state().library.albums)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()

        assertEquals(2, api.albumCalls)
        assertEquals(listOf(album), host.state().library.albums)
    }

    @Test
    fun `成功落地后收起加载圈`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.success(listOf(album))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        // 开拉即置位（骨架屏）
        assertTrue(host.state().session.isLoading)
        advanceUntilIdle()

        assertFalse(host.state().session.isLoading)
    }

    @Test
    fun `空列表表示拉到了但服务端没数据，之后不再重复请求`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.success(emptyList())
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        assertEquals(emptyList<Album>(), host.state().library.albums)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        assertEquals(1, api.albumCalls)
    }

    @Test
    fun `401 走会话过期，不写错误文案`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.failure(JellyfinError.Unauthorized())
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()

        assertEquals(1, host.expireCalls)
        assertNull(host.state().library.albums)
    }

    @Test
    fun `断网进离线态而不是报错，播放列表带需要联网标记`() = runTest {
        val api = FakeApi()
        api.playlistsResult = Result.failure(JellyfinError.Network(IllegalStateException()))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Playlists)
        advanceUntilIdle()

        assertEquals(listOf(true), host.offlineRequests)
        assertNull(host.state().library.playlists)
        assertNull(host.state().session.errorMessage)
    }

    // ── 发现页三节独立降级 ──────────────────────────────────────

    @Test
    fun `发现页一节失败只标记那一节，其余照常`() = runTest {
        val api = FakeApi()
        api.recentlyPlayedResult = Result.failure(JellyfinError.Http(500))
        api.mostPlayedResult = Result.success(listOf(track))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Discover)
        advanceUntilIdle()

        assertNull(host.state().library.recentlyPlayed)
        assertEquals(listOf(track), host.state().library.mostPlayed)
        assertEquals(setOf(LibrarySectionKey.RecentlyPlayed), host.state().library.discoverFailedSections)
        // 单节失败不升级成整页错误
        assertNull(host.state().session.errorMessage)
    }

    @Test
    fun `重新发起加载会清掉该节的失败标记`() = runTest {
        val api = FakeApi()
        api.recentlyPlayedResult = Result.failure(JellyfinError.Http(500))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.loadRecentlyPlayed()
        advanceUntilIdle()
        assertTrue(LibrarySectionKey.RecentlyPlayed in host.state().library.discoverFailedSections)

        api.recentlyPlayedResult = Result.success(listOf(track))
        cache.loadRecentlyPlayed()
        assertFalse(LibrarySectionKey.RecentlyPlayed in host.state().library.discoverFailedSections)
        advanceUntilIdle()

        assertEquals(listOf(track), host.state().library.recentlyPlayed)
    }

    @Test
    fun `三节历史各自幂等，重复进入不重复请求`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Discover)
        advanceUntilIdle()
        cache.onSectionEntered(LibrarySection.Discover)
        advanceUntilIdle()

        assertEquals(1, api.recentlyPlayedCalls)
        assertEquals(1, api.mostPlayedCalls)
        assertEquals(1, api.recentlyAddedCalls)
        assertEquals(0, api.albumCalls) // 发现页只管随机墙，不拉专辑列表
        assertEquals(1, api.randomAlbumsCalls)
    }

    @Test
    fun `切歌后历史版本号变化会作废在途响应并补跑当前分区`() = runTest {
        val api = FakeApi()
        val stale = Track("stale-1", "旧的", "歌手", "专辑", 1L, null)
        api.recentlyPlayedResult = Result.success(listOf(stale))
        val host = FakeHost(connection(api))
        val cache = newCache(host)
        cache.onSectionEntered(LibrarySection.Discover)
        advanceUntilIdle()
        assertEquals(listOf(stale), host.state().library.recentlyPlayed)

        // 版本号推进（模拟 ViewModel 的 onMediaItemTransition）+ 在途响应回来
        host.currentSection = LibrarySection.Discover
        val gate = CompletableDeferred<Unit>()
        api.historyGate = gate
        host.update { it.copy(library = it.library.copy(historyEpoch = it.library.historyEpoch + 1)) }
        cache.onHistoryEpochChanged()
        advanceUntilIdle()
        api.recentlyPlayedResult = Result.success(listOf(track))
        gate.complete(Unit)
        advanceUntilIdle()

        // 旧数据先留着（不闪空），重拉成功后原地替换
        assertEquals(listOf(track), host.state().library.recentlyPlayed)
        assertEquals(2, api.recentlyPlayedCalls)
    }

    @Test
    fun `换一批会重拉随机墙与三节历史，全部落地才收刷新圈`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        val randomGate = CompletableDeferred<Unit>()
        api.historyGate = gate
        api.randomAlbumsGate = randomGate
        val host = FakeHost(connection(api))
        val cache = newCache(host)
        cache.onSectionEntered(LibrarySection.Discover)
        advanceUntilIdle()

        cache.refreshDiscover()
        assertTrue(host.state().library.isDiscoverRefreshing)
        advanceUntilIdle()
        // 随机墙还挂着：刷新圈不能提前收
        assertTrue(host.state().library.isDiscoverRefreshing)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(host.state().library.isDiscoverRefreshing)

        randomGate.complete(Unit)
        advanceUntilIdle()
        assertFalse(host.state().library.isDiscoverRefreshing)
        assertEquals(2, api.randomAlbumsCalls)
        assertEquals(2, api.recentlyPlayedCalls)
    }

    // ── 离线翻转 ───────────────────────────────────────────────

    @Test
    fun `断网翻到离线后会按当前分区补跑`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.failure(JellyfinError.Network(IllegalStateException()))
        // 补跑会再发一次；网络还没恢复，所以第二次也失败（生产里就是这样）
        api.albumResults += Result.failure(JellyfinError.Network(IllegalStateException()))
        val host = FakeHost(connection(api))
        // 生产里「当前分区」由 ViewModel 在 onSectionEntered 时记住
        host.currentSection = LibrarySection.Albums
        val cache = newCache(host)
        cache.onSectionEntered(LibrarySection.Albums)

        advanceUntilIdle()

        // 进了离线态，并且因为连通性翻转补跑了当前分区（专辑仍为 null，下次进页可重试）
        assertTrue(host.state().library.isOffline)
        assertEquals(1, host.reloadCalls)
        assertNull(host.state().library.albums)
    }

    @Test
    fun `请求成功自动退出离线态`() = runTest {
        val api = FakeApi()
        api.albumResults += Result.success(listOf(album))
        val host = FakeHost(connection(api))
        host.update { it.copy(library = it.library.copy(isOffline = true)) }
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()

        assertFalse(host.state().library.isOffline)
        assertEquals(listOf(album), host.state().library.albums)
        assertEquals(1, host.persisted)
    }

    // ── 详情页 ─────────────────────────────────────────────────

    @Test
    fun `进专辑详情先清空旧曲目再拉新的`() = runTest {
        val api = FakeApi()
        api.albumTracksResult = Result.success(listOf(track))
        val host = FakeHost(connection(api))
        host.update { it.copy(library = it.library.copy(albumTracks = listOf(Track("old", "旧", "a", "b", 1L, null)))) }
        val cache = newCache(host)

        cache.loadAlbumTracks(album)
        // 开拉即清：界面不当成「已经加载好了」
        assertNull(host.state().library.albumTracks)
        assertEquals(album, host.state().library.selectedAlbum)
        advanceUntilIdle()

        assertEquals(listOf(track), host.state().library.albumTracks)
    }

    @Test
    fun `同一张专辑重复进入不重复请求，换专辑会重拉`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.loadAlbumTracks(album)
        advanceUntilIdle()
        cache.loadAlbumTracks(album)
        advanceUntilIdle()
        assertEquals(1, api.albumTracksCalls)

        cache.loadAlbumTracks(otherAlbum)
        advanceUntilIdle()
        assertEquals(2, api.albumTracksCalls)
        assertEquals(otherAlbum, host.state().library.selectedAlbum)
    }

    @Test
    fun `在线打开播放列表详情会记进已下载列表清单`() = runTest {
        val api = FakeApi()
        api.playlistTracksResult = Result.success(listOf(track))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.loadPlaylistTracks(playlist)
        advanceUntilIdle()

        assertEquals(listOf(playlist), host.rememberedPlaylists)
    }

    // ── 离线本地路径 ─────────────────────────────────────────────

    @Test
    fun `离线进播放列表走本地清单，不发网络请求`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        host.update { it.copy(library = it.library.copy(isOffline = true)) }
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Playlists)
        advanceUntilIdle()

        assertEquals(1, host.offlinePlaylistLoads)
        assertEquals(0, api.playlistsCalls)
    }

    @Test
    fun `离线打开没下载过的播放列表走本地清单并提示需要联网`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        host.update { it.copy(library = it.library.copy(isOffline = true)) }
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.PlaylistDetail(playlist))
        advanceUntilIdle()

        assertEquals(listOf(playlist), host.offlinePlaylistTrackLoads)
        assertEquals(0, api.playlistTracksCalls)
    }

    // ── 搜索 ───────────────────────────────────────────────────

    @Test
    fun `搜索防抖 300ms，只发最后一次查询`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.search("old")
        advanceTimeBy(100)
        cache.search("latest")
        advanceTimeBy(299)
        assertEquals(emptyList<String>(), api.searchQueries)
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(listOf("latest"), api.searchQueries)
    }

    @Test
    fun `清空搜索词立即清结果并取消在途请求`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.search("query")
        cache.search("")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), api.searchQueries)
        assertNull(host.state().library.search.results)
        assertFalse(host.state().library.search.isSearching)
    }

    @Test
    fun `离线搜索走快照并标明离线搜索`() = runTest {
        val api = FakeApi()
        val host = FakeHost(connection(api))
        host.update { it.copy(library = it.library.copy(isOffline = true)) }
        host.snapshotResults = SearchResults(tracks = listOf(track))
        val cache = newCache(host)

        cache.search("歌")
        advanceUntilIdle()

        assertEquals(listOf(track), host.state().library.search.results?.tracks)
        assertTrue(host.state().library.search.isOfflineSearch)
        assertEquals(emptyList<String>(), api.searchQueries)
    }

    // ── 会话切换 ───────────────────────────────────────────────

    @Test
    fun `会话切换后旧请求的响应不会写进新会话`() = runTest {
        val api = FakeApi()
        val gate = CompletableDeferred<Unit>()
        api.albumGates += gate
        api.albumResults += Result.success(listOf(album))
        val host = FakeHost(connection(api))
        val cache = newCache(host)

        cache.onSectionEntered(LibrarySection.Albums)
        advanceUntilIdle()
        cache.invalidate()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(host.state().library.albums)
    }

    // ── 测试脚手架 ──────────────────────────────────────────────

    private fun TestScope.newCache(host: FakeHost): LibraryCache {
        val cache = LibraryCache(
            scope = this,
            dispatcher = StandardTestDispatcher(testScheduler),
            describeError = { error ->
                when (error) {
                    is JellyfinError.Http -> "服务器请求失败（${error.statusCode}）"
                    is JellyfinError.Network -> "无法连接服务器，请检查地址和网络"
                    else -> "加载失败，请稍后重试"
                }
            },
            host = host,
        )
        host.cache = cache
        return cache
    }

    private fun connection(api: JellyfinApi) =
        LibraryCache.Connection(api, JellyfinSession("user-1", "token-1"))

    /** 与 `JellyfinViewModel` 接缝行为一致的 Fake 宿主：状态放真实的 `JellyfinUiState` 里。 */
    private class FakeHost(var connection: LibraryCache.Connection?) : LibraryCache.Host {
        var cache: LibraryCache? = null
        private var uiState = JellyfinUiState()
        var expireCalls = 0
        var persisted = 0
        var reloadCalls = 0
        val offlineRequests = mutableListOf<Boolean>()
        val rememberedPlaylists = mutableListOf<Playlist>()
        var offlinePlaylistLoads = 0
        val offlinePlaylistTrackLoads = mutableListOf<Playlist>()
        var snapshotResults: SearchResults? = null

        override fun state(): JellyfinUiState = uiState

        override fun update(transform: (JellyfinUiState) -> JellyfinUiState) {
            uiState = transform(uiState)
        }

        override fun connection(): LibraryCache.Connection? = connection

        override fun expireSession() {
            expireCalls++
        }

        override suspend fun markOnline(): Boolean {
            if (!uiState.library.isOffline) return false
            // 与生产一致：翻回在线时清掉快照来源的几屏
            uiState = uiState.copy(
                library = uiState.library.copy(
                    isOffline = false,
                    albums = null,
                    discoverAlbums = null,
                    albumTracks = null,
                    recentlyPlayed = null,
                    mostPlayed = null,
                    recentlyAdded = null,
                    discoverFailedSections = emptySet(),
                )
            )
            return true
        }

        override suspend fun goOffline(needsNetwork: Boolean): Boolean {
            offlineRequests += needsNetwork
            if (uiState.library.isOffline) return false
            uiState = uiState.copy(library = uiState.library.copy(isOffline = true))
            return true
        }

        override suspend fun persistSnapshot() {
            persisted++
        }

        override fun rememberPlaylistTracks(playlist: Playlist, tracks: List<Track>) {
            rememberedPlaylists += playlist
        }

        override suspend fun loadOfflinePlaylists() {
            offlinePlaylistLoads++
        }

        override suspend fun loadOfflinePlaylistTracks(playlist: Playlist) {
            offlinePlaylistTrackLoads += playlist
        }

        override fun searchSnapshot(query: String): SearchResults? = snapshotResults

        override fun reloadCurrentSection() {
            reloadCalls++
            requestReload()
        }

        /** 生产里是 `currentSection` 记忆 + `enterSection`；测试显式触发。 */
        var currentSection: LibrarySection? = null

        fun requestReload() {
            currentSection?.let { cache?.onSectionEntered(it) }
        }
    }

    /** 每个方法都能挂起（gate）与排队结果，用来复现「响应还没回来就换了页面」。 */
    private class FakeApi : JellyfinApi {
        val albumResults = ArrayDeque<Result<List<Album>>>()
        val albumGates = ArrayDeque<CompletableDeferred<Unit>>()
        var albumCalls = 0
        var randomAlbumsCalls = 0
        var albumTracksCalls = 0
        var playlistsCalls = 0
        var playlistTracksCalls = 0
        var recentlyPlayedCalls = 0
        var mostPlayedCalls = 0
        var recentlyAddedCalls = 0
        val searchQueries = mutableListOf<String>()
        var historyGate: CompletableDeferred<Unit>? = null

        var albumTracksResult: Result<List<Track>> = Result.success(emptyList())
        var playlistsResult: Result<List<Playlist>> = Result.success(emptyList())
        var playlistTracksResult: Result<List<Track>> = Result.success(emptyList())
        var recentlyPlayedResult: Result<List<Track>> = Result.success(emptyList())
        var mostPlayedResult: Result<List<Track>> = Result.success(emptyList())
        var recentlyAddedResult: Result<List<Album>> = Result.success(emptyList())

        override suspend fun getAlbums(userId: String, accessToken: String): Result<List<Album>> {
            albumCalls++
            // 先取走自己那份结果再挂起：被取消的请求也消费掉它，别让下一次请求拿到旧结果
            val result = albumResults.removeFirstOrNull() ?: Result.success(emptyList())
            albumGates.removeFirstOrNull()?.await()
            return result
        }

        var randomAlbumsGate: CompletableDeferred<Unit>? = null

        override suspend fun getRandomAlbums(userId: String, accessToken: String, limit: Int): Result<List<Album>> {
            randomAlbumsCalls++
            randomAlbumsGate?.await()
            return Result.success(emptyList())
        }

        override suspend fun getAlbumTracks(userId: String, accessToken: String, albumId: String) =
            albumTracksResult.also { albumTracksCalls++ }

        var playlistsGate: CompletableDeferred<Unit>? = null

        override suspend fun getPlaylists(userId: String, accessToken: String): Result<List<Playlist>> {
            playlistsCalls++
            playlistsGate?.await()
            return playlistsResult
        }

        override suspend fun getPlaylistTracks(userId: String, accessToken: String, playlistId: String) =
            playlistTracksResult.also { playlistTracksCalls++ }

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

        override suspend fun search(userId: String, accessToken: String, query: String): Result<SearchResults> {
            searchQueries += query
            return Result.success(SearchResults())
        }

        override suspend fun login(username: String, password: String) =
            Result.failure<JellyfinSession>(IllegalStateException())

        override suspend fun getMusicItems(userId: String, accessToken: String) =
            Result.success(emptyList<Track>())

        override suspend fun getLyrics(accessToken: String, trackId: String) = Result.success(Lyrics.NONE)
        override fun streamUrl(mediaId: String, accessToken: String) = "stream/$mediaId"
        override suspend fun reportPlaybackStarted(accessToken: String, itemId: String, positionTicks: Long) =
            Result.success(Unit)
        override suspend fun reportPlaybackStopped(accessToken: String, itemId: String, positionTicks: Long) =
            Result.success(Unit)
        override fun imageRequest(itemId: String, imageTag: String?, accessToken: String) =
            JellyfinRequest("image", emptyMap())
        override fun authorizationHeader(accessToken: String) = "auth-header"
    }
}
