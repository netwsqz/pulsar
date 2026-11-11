package com.example.pulsar.library

import com.example.pulsar.JellyfinUiState
import com.example.pulsar.LibrarySection
import com.example.pulsar.data.jellyfin.JellyfinApi
import com.example.pulsar.data.jellyfin.JellyfinError
import com.example.pulsar.data.jellyfin.JellyfinSession
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.state.LibrarySectionKey
import com.example.pulsar.state.LibraryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** 搜索防抖：每敲一个字都发一次请求没有意义（离线时也一样，快照过滤要 CPU）。 */
internal const val LIBRARY_SEARCH_DEBOUNCE_MS = 300L

/**
 * 曲库加载 module（`docs/library-state-refactor.md` Step 2 / #12）。
 *
 * **每节一份 job + 代次，互不取消。** 改前所有加载共用一个 `albumsJob`，进播放列表会把
 * 在途的专辑列表请求取消掉——那是有意修掉的 bug。这里 [LibrarySlot] 一节一个槽：
 * 同一节重复进入取消旧请求（不发重复网络请求），不同节各跑各的。
 *
 * **三态语义只在这里实现**（改前三处各写一遍）：
 *
 * - `null` = 没拉到 / 失败：界面显示错误或骨架，下次进这一节会重试；
 * - `空列表` = 拉到了但服务端没数据：界面显示空态；
 * - **失败绝不写空列表**——一次网络抖动会被幂等守卫固化成「已加载且没有数据」，到进程结束都不再请求。
 *
 * **会话未就绪**（[Host.connection] 为 null）时静默跳过：不置加载中、不报错；由
 * [Host.reloadCurrentSection] 在会话就绪后补跑当前分区。
 *
 * 本类不持有状态、不知道 token 长什么样：状态读写、快照落盘、离线态、会话过期全部经
 * [Host] 转给 `JellyfinViewModel`。界面只调 [onSectionEntered]（或失败后的单个重试）。
 *
 * 线程约束：入口都在主线程（宿主给的 [dispatcher]）上调用，每节的槽表不加锁——
 * 与 `JellyfinViewModel` 的 `_state` 同一口径。
 */
internal class LibraryCache(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    /** 加载失败的界面文案（与登录错误共用 `JellyfinViewModel.friendlyError`）。 */
    private val describeError: (Throwable) -> String,
    private val host: Host,
) {
    /** 一次加载需要的两样东西：API 与当前会话；null 表示会话还没就绪。 */
    data class Connection(val api: JellyfinApi, val session: JellyfinSession)

    /** 宿主接缝：状态、会话、离线态与快照都归 `JellyfinViewModel`，module 只做加载编排。 */
    interface Host {
        /** 组合根本体（曲库 slice 的幂等守卫从这读）。 */
        fun state(): JellyfinUiState

        /** 只走一次 `_state.value = ...` 的写入口，保证一次加载的多次字段修改是原子的。 */
        fun update(transform: (JellyfinUiState) -> JellyfinUiState)

        /** 会话就绪时返回连接；null = 还没就绪（加载静默跳过，等 [reloadCurrentSection] 补跑）。 */
        fun connection(): Connection?

        /** 401：会话失效，走统一的过期流程。 */
        fun expireSession()

        /** 一次请求成功：退出离线态。返回 true = 本次真的从离线翻回在线（快照来源的屏已被清）。 */
        suspend fun markOnline(): Boolean

        /** 断网：进离线态。返回 true = 本次真的从在线翻到离线。 */
        suspend fun goOffline(needsNetwork: Boolean): Boolean

        /** 成功落地后写曲库快照（铁律 4：快照只在线下兜底，不在线上抢活）。 */
        suspend fun persistSnapshot()

        /** 在线打开播放列表详情后，记住「列表 → 曲目表」（工单 08）。 */
        fun rememberPlaylistTracks(playlist: Playlist, tracks: List<Track>)

        /** 离线时的播放列表列表：走本地清单（快照 + 名单），一个都没下载就提示需要联网。 */
        suspend fun loadOfflinePlaylists()

        /** 离线时打开播放列表详情：已下载的读本地清单，没下载的提示需要联网。 */
        suspend fun loadOfflinePlaylistTracks(playlist: Playlist)

        /** 离线搜索：在曲库快照里过滤；null = 没有快照（当空结果）。 */
        fun searchSnapshot(query: String): SearchResults?

        /** 会话就绪 / 离线翻转 / 切歌后：按「当前所在分区」补跑一次。 */
        fun reloadCurrentSection()
    }

    /** 一节加载的槽：一份在途请求 + 一个代次（响应回来时对不上就丢掉）。 */
    private class Slot {
        var job: Job? = null
        var generation = 0
    }

    private enum class LibrarySlot {
        Albums,
        DiscoverAlbums,
        RecentlyPlayed,
        MostPlayed,
        RecentlyAdded,
        AlbumTracks,
        Playlists,
        PlaylistTracks,
        Search,
    }

    private val slots = LibrarySlot.entries.associateWith { Slot() }

    /**
     * 正在跑的、会写 `isLoading` 的节。共享的加载圈要等它们**全部**落地才收。
     *
     * 改前所有加载共用一个 job，谁完成谁收圈都不会出错；#12 之后多节可以同时在途，
     * 后台那一节先完成会把圈收掉，当前屏就会从骨架掉进空态（「暂无播放列表」闪一下）。
     */
    private val loadingSlots = mutableSetOf<LibrarySlot>()

    /** 「换一批」要等落地的那四节。 */
    private val REFRESH_SLOTS = listOf(
        LibrarySlot.DiscoverAlbums,
        LibrarySlot.RecentlyPlayed,
        LibrarySlot.MostPlayed,
        LibrarySlot.RecentlyAdded,
    )

    /** 三节历史各自记住「已拉到的 [LibraryState.historyEpoch]」：相等且数据非空就不重复请求。 */
    private var recentlyPlayedEpoch = -1
    private var mostPlayedEpoch = -1
    private var recentlyAddedEpoch = -1

    private val library: LibraryState get() = host.state().library

    // ── 入口：界面只报「进了哪一节」 ────────────────────────────────

    /**
     * 进入导航分区。界面不再知道加载方法名、幂等守卫与会话是否就绪（#12）。
     * 歌曲页的曲目来自登录/恢复时拉的 `tracks`；搜索、设置、已下载、QQ 导入各有自己的入口。
     */
    fun onSectionEntered(section: LibrarySection) {
        when (section) {
            LibrarySection.Discover -> {
                loadDiscoverAlbums()
                loadRecentlyPlayed()
                loadMostPlayed()
                loadRecentlyAdded()
            }
            LibrarySection.Albums,
            LibrarySection.Artists,
            is LibrarySection.GroupDetail -> loadAlbums()
            LibrarySection.Playlists -> loadPlaylists()
            is LibrarySection.AlbumDetail -> loadAlbumTracks(section.album)
            is LibrarySection.PlaylistDetail -> loadPlaylistTracks(section.playlist)
            // 已下载页需要曲库 tracks + 专辑分组，由 ViewModel 在 enterSection 里拦截，到不了这里。
            LibrarySection.Tracks,
            LibrarySection.Search,
            LibrarySection.Settings,
            LibrarySection.CachedCopies,
            LibrarySection.DownloadService -> Unit
        }
    }

    /** 发现页「换一批」：随机专辑 + 三节历史全部重拉，四路都落地才收刷新圈。 */
    fun refreshDiscover() {
        // 三节历史**不清数据、只递增版本号**：旧列表留在屏幕上，新结果回来原地替换（不闪骨架）。
        host.update {
            it.copy(
                library = it.library.copy(
                    historyEpoch = it.library.historyEpoch + 1,
                    discoverFailedSections = emptySet(),
                    isDiscoverRefreshing = true,
                )
            )
        }
        loadDiscoverAlbums(force = true)
        loadRecentlyPlayed()
        loadMostPlayed()
        loadRecentlyAdded()
        scope.launch(dispatcher) {
            // 四路可能被切歌（onHistoryEpochChanged）/ 离线翻转 / 二次刷新替换掉：不能只等
            // 发起时抓到的那几个 Job（它们被取消后 joinAll 立刻返回，刷新圈会提前消失）。
            // 以槽里「当前那份 job」为准，直到四路都不在跑才收圈。
            while (true) {
                val running = REFRESH_SLOTS.mapNotNull { slot ->
                    slots.getValue(slot).job?.takeIf { it.isActive }
                }
                if (running.isEmpty()) break
                running.joinAll()
            }
            host.update { it.copy(library = it.library.copy(isDiscoverRefreshing = false)) }
        }
    }

    // ── 各节加载 ────────────────────────────────────────────────

    /** 专辑列表。**幂等**：已加载就不再请求；[force] 给发现页「换一批」用。 */
    fun loadAlbums(force: Boolean = false) {
        if (!force && library.albums != null) return
        loadLibrary(
            slot = LibrarySlot.Albums,
            request = { it.api.getAlbums(it.session.userId, it.session.accessToken) },
            apply = { lib, albums -> lib.copy(albums = albums) },
        )
    }

    /** 发现页随机专辑墙。[force] 给「换一批」用（随机墙本来就是每批新的）。 */
    fun loadDiscoverAlbums(force: Boolean = false) {
        if (!force && library.discoverAlbums != null) return
        loadLibrary(
            slot = LibrarySlot.DiscoverAlbums,
            request = { it.api.getRandomAlbums(it.session.userId, it.session.accessToken) },
            apply = { lib, albums -> lib.copy(discoverAlbums = albums) },
        )
    }

    fun loadRecentlyPlayed() {
        val epoch = library.historyEpoch
        if (library.recentlyPlayed != null && recentlyPlayedEpoch == epoch) return
        loadHistory(
            slot = LibrarySlot.RecentlyPlayed,
            section = LibrarySectionKey.RecentlyPlayed,
            request = { it.api.getRecentlyPlayed(it.session.userId, it.session.accessToken) },
            apply = { lib, tracks ->
                recentlyPlayedEpoch = epoch
                lib.copy(recentlyPlayed = tracks)
            },
        )
    }

    fun loadMostPlayed() {
        val epoch = library.historyEpoch
        if (library.mostPlayed != null && mostPlayedEpoch == epoch) return
        loadHistory(
            slot = LibrarySlot.MostPlayed,
            section = LibrarySectionKey.MostPlayed,
            request = { it.api.getMostPlayed(it.session.userId, it.session.accessToken) },
            apply = { lib, tracks ->
                mostPlayedEpoch = epoch
                lib.copy(mostPlayed = tracks)
            },
        )
    }

    fun loadRecentlyAdded() {
        val epoch = library.historyEpoch
        if (library.recentlyAdded != null && recentlyAddedEpoch == epoch) return
        loadHistory(
            slot = LibrarySlot.RecentlyAdded,
            section = LibrarySectionKey.RecentlyAdded,
            request = { it.api.getRecentlyAdded(it.session.userId, it.session.accessToken) },
            apply = { lib, albums ->
                recentlyAddedEpoch = epoch
                lib.copy(recentlyAdded = albums)
            },
        )
    }

    fun loadAlbumTracks(album: Album) {
        val current = library
        if (current.selectedAlbum?.id == album.id && current.albumTracks != null) return
        loadLibrary(
            slot = LibrarySlot.AlbumTracks,
            before = { it.copy(selectedAlbum = album, albumTracks = null) },
            request = { it.api.getAlbumTracks(it.session.userId, it.session.accessToken, album.id) },
            apply = { lib, tracks -> lib.copy(albumTracks = tracks) },
        )
    }

    fun loadPlaylists() {
        // 离线：只显示「下载过的」播放列表（工单 08 / CONTEXT「已下载列表清单」）。
        // 放在幂等守卫之前——在线拉过的全量列表不能挡着离线筛选。
        if (library.isOffline) {
            launchLocal(LibrarySlot.Playlists) { host.loadOfflinePlaylists() }
            return
        }
        if (library.playlists != null) return
        loadLibrary(
            slot = LibrarySlot.Playlists,
            needsNetwork = true,
            request = { it.api.getPlaylists(it.session.userId, it.session.accessToken) },
            apply = { lib, playlists -> lib.copy(playlists = playlists) },
        )
    }

    fun loadPlaylistTracks(playlist: Playlist) {
        val current = library
        if (current.selectedPlaylist?.id == playlist.id && current.playlistTracks != null) return
        // 离线：已下载过的从本地清单里读，没下载过的照旧「需要联网」。
        if (current.isOffline) {
            launchLocal(LibrarySlot.PlaylistTracks) { host.loadOfflinePlaylistTracks(playlist) }
            return
        }
        loadLibrary(
            slot = LibrarySlot.PlaylistTracks,
            needsNetwork = true,
            before = { it.copy(selectedPlaylist = playlist, playlistTracks = null) },
            request = { it.api.getPlaylistTracks(it.session.userId, it.session.accessToken, playlist.id) },
            apply = { lib, tracks -> lib.copy(playlistTracks = tracks) },
            // 在线拿到曲目表 = 顺手把「已下载列表清单」刷新一份（列表名 + 曲目表）。
            afterSuccess = { tracks -> host.rememberPlaylistTracks(playlist, tracks) },
        )
    }

    /**
     * 搜索（防抖 300ms，独立 job + 代次）。
     *
     * 断网时改成在曲库快照里过滤（用户故事 10），并标记 [com.example.pulsar.state.SearchState.isOfflineSearch]：
     * 范围比服务端搜索小，界面要标明。查询词本身由界面先写进 state，这里只管结果。
     */
    fun search(query: String) {
        val slot = slots.getValue(LibrarySlot.Search)
        slot.job?.cancel()
        slot.job = null
        val generation = ++slot.generation
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            // 立即清除结果、不发请求（改前语义）。
            host.update {
                it.copy(
                    library = it.library.copy(
                        search = it.library.search.copy(
                            results = null,
                            isSearching = false,
                            isOfflineSearch = false,
                        )
                    )
                )
            }
            return
        }
        val connection = host.connection() ?: return
        host.update {
            it.copy(library = it.library.copy(search = it.library.search.copy(isSearching = true)))
        }
        slot.job = scope.launch(dispatcher) {
            // 防抖对本地过滤也照旧：每敲一个字都重算一遍快照没有意义。
            delay(LIBRARY_SEARCH_DEBOUNCE_MS)
            if (!isActive || generation != slot.generation) return@launch
            if (library.isOffline) {
                val results = host.searchSnapshot(trimmed) ?: SearchResults()
                if (isActive && generation == slot.generation) {
                    applySearch(generation, results, offline = true)
                }
                return@launch
            }
            try {
                val results = connection.api
                    .search(connection.session.userId, connection.session.accessToken, trimmed)
                    .getOrThrow()
                if (isActive && generation == slot.generation) {
                    applySearch(generation, results, offline = false)
                    if (host.markOnline()) host.reloadCurrentSection()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (generation != slot.generation) return@launch
                when (error) {
                    is JellyfinError.Unauthorized -> host.expireSession()
                    // 网断了：拿快照里的结果顶上，并标明这是离线搜索。
                    is JellyfinError.Network -> {
                        val flipped = host.goOffline(needsNetwork = false)
                        applySearch(generation, host.searchSnapshot(trimmed) ?: SearchResults(), offline = true)
                        if (flipped) host.reloadCurrentSection()
                    }
                    else -> host.update {
                        it.copy(
                            session = it.session.copy(errorMessage = describeError(error)),
                            library = it.library.copy(search = it.library.search.copy(isSearching = false)),
                        )
                    }
                }
            }
        }
    }

    private fun applySearch(generation: Int, results: SearchResults, offline: Boolean) {
        if (generation != slots.getValue(LibrarySlot.Search).generation) return
        host.update {
            it.copy(
                library = it.library.copy(
                    search = it.library.search.copy(
                        isSearching = false,
                        results = results,
                        isOfflineSearch = offline,
                    )
                )
            )
        }
    }

    // ── 会话 / 版本号变化 ─────────────────────────────────────────

    /**
     * 切歌后播放历史版本号变了：**只作废三节历史的在途响应**（不清数据，界面静默重拉）。
     * 与改前 `historyGeneration++` 等价；再按当前分区补跑一次（原先靠界面把 `historyEpoch`
     * 当 LaunchedEffect key 做到这件事）。
     */
    fun onHistoryEpochChanged() {
        listOf(
            LibrarySlot.RecentlyPlayed,
            LibrarySlot.MostPlayed,
            LibrarySlot.RecentlyAdded,
        ).forEach { slots.getValue(it).generation++ }
        host.reloadCurrentSection()
    }

    /**
     * 会话切换 / 退出登录 / 会话过期：作废所有在途请求（含离线本地路径）。
     * **不清状态**——清什么由宿主按 slice 决定（#13 收口 reset 语义）。
     */
    fun invalidate() {
        slots.values.forEach { slot ->
            slot.job?.cancel()
            slot.job = null
            slot.generation++
        }
        loadingSlots.clear()
        recentlyPlayedEpoch = -1
        mostPlayedEpoch = -1
        recentlyAddedEpoch = -1
    }

    fun clearAlbumDetail() {
        cancel(LibrarySlot.AlbumTracks)
        host.update {
            it.copy(
                session = it.session.copy(errorMessage = null),
                library = it.library.copy(selectedAlbum = null),
            )
        }
    }

    fun clearPlaylistDetail() {
        cancel(LibrarySlot.PlaylistTracks)
        host.update {
            it.copy(
                session = it.session.copy(errorMessage = null),
                library = it.library.copy(selectedPlaylist = null),
            )
        }
    }

    /** 等专辑列表这一节落地（已下载页分组前要先有专辑对象）。 */
    suspend fun awaitAlbums() {
        slots.getValue(LibrarySlot.Albums).job?.join()
    }

    // ── 内部：加载编排 ───────────────────────────────────────────

    /**
     * 一节**会落快照**的曲库加载（专辑 / 发现随机 / 专辑曲目 / 播放列表 / 播放列表曲目）。
     *
     * 失败语义见类注释：字段保持 `null`，绝不写空列表；401 / 断网交给宿主统一处理。
     */
    private fun <T> loadLibrary(
        slot: LibrarySlot,
        needsNetwork: Boolean = false,
        before: ((LibraryState) -> LibraryState)? = null,
        request: suspend (Connection) -> Result<T>,
        apply: (LibraryState, T) -> LibraryState,
        afterSuccess: (T) -> Unit = {},
    ) {
        val connection = host.connection() ?: return
        val current = slots.getValue(slot)
        current.job?.cancel()
        val generation = ++current.generation
        loadingSlots += slot
        host.update { state ->
            state.copy(
                session = state.session.copy(isLoading = true, errorMessage = null),
                library = before?.invoke(state.library) ?: state.library,
            )
        }
        current.job = scope.launch(dispatcher) {
            val result = runCatchingCancellable { request(connection) }
            if (!isActive || generation != current.generation) return@launch
            result.fold(
                onSuccess = { value ->
                    // 先退离线态再赋成功值：markOnline 会清掉快照来源的几屏，顺序反了会把刚拉到的新数据当快照数据清掉。
                    val flipped = host.markOnline()
                    val lastOne = finishLoading(slot, generation)
                    host.update { state ->
                        state.copy(
                            // 还有别的节在途时**不收圈**（否则当前屏会闪一下空态）。
                            session = state.session.copy(isLoading = state.session.isLoading && !lastOne),
                            library = apply(state.library, value),
                        )
                    }
                    host.persistSnapshot()
                    afterSuccess(value)
                    if (flipped) host.reloadCurrentSection()
                },
                onFailure = { error -> handleFailure(slot, generation, error, needsNetwork) },
            )
        }
    }

    /**
     * 发现页一节历史：失败只是这一节没内容（其余 section 照常），**不**升级成整页错误、
     * 不动 `isLoading`、不落快照。失败时字段保持 null（幂等守卫据此允许下次进页重试）。
     */
    private fun <T> loadHistory(
        slot: LibrarySlot,
        section: LibrarySectionKey,
        request: suspend (Connection) -> Result<T>,
        apply: (LibraryState, T) -> LibraryState,
    ) {
        val connection = host.connection() ?: return
        val current = slots.getValue(slot)
        current.job?.cancel()
        val generation = ++current.generation
        // 这一节重新发起请求：清掉它的失败标记，界面重新显示骨架（重试可见）。
        if (section in library.discoverFailedSections) {
            host.update {
                it.copy(
                    library = it.library.copy(
                        discoverFailedSections = it.library.discoverFailedSections - section,
                    )
                )
            }
        }
        current.job = scope.launch(dispatcher) {
            val result = runCatchingCancellable { request(connection) }
            if (!isActive || generation != current.generation) return@launch
            result.fold(
                onSuccess = { value ->
                    val flipped = host.markOnline()
                    host.update { state -> state.copy(library = apply(state.library, value)) }
                    if (flipped) host.reloadCurrentSection()
                },
                onFailure = { error ->
                    when (error) {
                        is JellyfinError.Unauthorized -> host.expireSession()
                        // 断网照旧进离线态（探活/离线可见性全 App 共用），不是这一节自己的事
                        is JellyfinError.Network ->
                            if (host.goOffline(needsNetwork = false)) host.reloadCurrentSection()
                        // 其余失败：这一节缺席，界面不再为它显示骨架（否则永远转圈）
                        else -> host.update {
                            it.copy(
                                library = it.library.copy(
                                    discoverFailedSections = it.library.discoverFailedSections + section,
                                )
                            )
                        }
                    }
                },
            )
        }
    }

    private suspend fun handleFailure(
        slot: LibrarySlot,
        generation: Int,
        error: Throwable,
        needsNetwork: Boolean,
    ) {
        // 先把自己从「在途」里摘掉：401 / 断网的分支由宿主统一复位 isLoading，这里只看集合。
        val lastOne = finishLoading(slot, generation)
        when (error) {
            is JellyfinError.Unauthorized -> host.expireSession()
            // 网络不通 ≠ 请求出错：退回快照顶上的离线态，不当成错误显示
            is JellyfinError.Network ->
                if (host.goOffline(needsNetwork)) host.reloadCurrentSection()
            else -> host.update {
                it.copy(
                    session = it.session.copy(
                        isLoading = it.session.isLoading && !lastOne,
                        errorMessage = describeError(error),
                    )
                )
            }
        }
    }

    /**
     * 一节加载落地（成功或失败）：从「在途」里摘掉。
     *
     * 返回 true = 这是最后一个在途的节，调用方可以把共享的 `isLoading` 收掉。
     * 代次对不上（已被同节新请求取代 / 已被作废）时不动集合——摘掉会把新请求的圈也收了。
     */
    private fun finishLoading(slot: LibrarySlot, generation: Int): Boolean {
        if (slots.getValue(slot).generation != generation) return false
        loadingSlots -= slot
        return loadingSlots.isEmpty()
    }

    /**
     * 离线时的本地清单路径（播放列表 / 播放列表曲目）：不走网络，也就没有代次判定——
     * 写状态的是宿主自己的本地逻辑。仍占这一节的槽：重复进入会取消上一次本地加载。
     */
    private fun launchLocal(slot: LibrarySlot, block: suspend () -> Unit) {
        // 先作废这一节可能在途的网络请求（连同它的加载圈登记），再跑本地清单路径。
        cancel(slot)
        slots.getValue(slot).job = scope.launch(dispatcher) { block() }
    }

    private fun cancel(slot: LibrarySlot) {
        val current = slots.getValue(slot)
        current.job?.cancel()
        current.job = null
        current.generation++
        loadingSlots -= slot
    }

    /** 统一「异常转 Result、CancellationException 原样抛」——与 `JellyfinDataSource.execute` 同口径。 */
    private suspend fun <T> runCatchingCancellable(block: suspend () -> Result<T>): Result<T> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
