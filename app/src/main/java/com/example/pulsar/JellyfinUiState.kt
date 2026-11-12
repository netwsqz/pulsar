package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.state.DownloadsState
import com.example.pulsar.state.LibraryState
import com.example.pulsar.state.SessionState

/**
 * 界面状态的**组合根**（`docs/library-state-refactor.md` Step 1 / #11）。
 *
 * 内部按**所有权**拆成三个 slice，组合后仍然只对外发一条 `StateFlow<JellyfinUiState>`：
 *
 * | slice | 装什么 | 登出 / 过期 |
 * |---|---|---|
 * | [session] | 登录表单、恢复会话、错误通道 | [SessionState.cleared]：登出全清；过期保留 `serverUrl` / `username`。地址历史永远保留 |
 * | [library] | 曲库、发现、详情、搜索、多选、文件操作、离线可见性 | [LibraryState.cleared]：整块清空 |
 * | [downloads] | 下载派生的界面状态 | [DownloadsState.cleared]：只清界面字段——ADR-0007 的「不清」说的是**字节与名单**，不是这里的 UI 字段 |
 *
 * **迁移期兼容层**：原来 36 个顶层字段的**读法**全部保留为只读属性（下面这一节），
 * 写法则走本类底部的兼容 `copy(...)`（同名参数，落到对应 slice）。这样屏幕代码一行都不用改，
 * 后续工单（#12–#16）按屏幕逐个迁到 `state.library.xxx` / `state.session.xxx`，
 * 最后 #16 删掉兼容层。**新代码请直接读写 slice，不要再加旧字段。**
 */
data class JellyfinUiState(
    val session: SessionState = SessionState(),
    val library: LibraryState = LibraryState(),
    val downloads: DownloadsState = DownloadsState(),
) {
    // ── 兼容层 · 旧字段读法（#11 加，#16 删）────────────────────────────
    // 一律只读、不存第二份「真相」：这些 val 在构造时从 slice 求值，而三个 slice 本身是
    // 不可变的 val，所以永远不会过期（每次 copy 都会重建它们）。字段含义见对应 slice 的 KDoc。
    // 不用 `get() =` 自定义 getter 的原因：自定义 getter 会让 `state.errorMessage != null`
    // 之后的智能转换失效，界面里 8 处 `when` 分支就编译不过了——「现有代码不改」是这一步的验收线。

    // 会话
    val serverUrl: String = session.serverUrl
    val serverUrlHistory: List<String> = session.serverUrlHistory
    val username: String = session.username
    val password: String = session.password
    val isLoading: Boolean = session.isLoading
    val isRestoring: Boolean = session.isRestoring
    val errorMessage: String? = session.errorMessage

    // 曲库
    val tracks: List<Track>? = library.tracks
    val discoverAlbums: List<Album>? = library.discoverAlbums
    val recentlyPlayed: List<Track>? = library.recentlyPlayed
    val mostPlayed: List<Track>? = library.mostPlayed
    val recentlyAdded: List<Album>? = library.recentlyAdded
    val discoverFailedSections: Set<com.example.pulsar.state.LibrarySectionKey> = library.discoverFailedSections
    val historyEpoch: Int = library.historyEpoch
    val isDiscoverRefreshing: Boolean = library.isDiscoverRefreshing
    val albums: List<Album>? = library.albums
    val albumTracks: List<Track>? = library.albumTracks
    val selectedAlbum: Album? = library.selectedAlbum
    val playlists: List<Playlist>? = library.playlists
    val playlistTracks: List<Track>? = library.playlistTracks
    val selectedPlaylist: Playlist? = library.selectedPlaylist
    val multiSelectIds: Set<String> = library.multiSelectIds
    val isOffline: Boolean = library.isOffline

    // 搜索
    val searchQuery: String = library.search.query
    val searchResults: SearchResults? = library.search.results
    val isSearching: Boolean = library.search.isSearching
    val isOfflineSearch: Boolean = library.search.isOfflineSearch

    // 文件操作（含回收站）
    val isFileOpsBusy: Boolean = library.fileOps.isBusy
    val fileOpsMessage: String? = library.fileOps.message
    val recycleFiles: List<com.example.pulsar.data.libraryfiles.LibraryRecycleFile> =
        library.fileOps.recycleFiles
    val isRecycleLoading: Boolean = library.fileOps.isRecycleLoading
    val recycleMessage: String? = library.fileOps.recycleMessage

    // 下载
    val cachedTrackIds: Set<String>? = downloads.visibleTrackIds
    val cachedAlbums: List<CachedAlbum>? = downloads.cachedAlbums
    val isDeletingDownload: Boolean = downloads.isDeleting
    val downloadDeleteMessage: String? = downloads.deleteMessage

    // ── reset 语义（#13）────────────────────────────────────────────────

    /**
     * 登出 / 会话过期：三个 slice 一起收进各自 slice 的 `cleared()`，不再手抄字段清单。
     *
     * 两条路径共用本方法（只是 [keepLoginForm] 与 [errorMessage] 不同），所以「过期比登出少清
     * 几个字段」那类不一致不会再出现。给 slice 新增字段会自动跟上——约定是「字段默认值 = 已清」。
     *
     * [errorMessage] 落在 [SessionState]：它是登录与曲库共用的错误通道（#11 / #12 口径未变）。
     */
    fun cleared(keepLoginForm: Boolean = false, errorMessage: String? = null): JellyfinUiState = JellyfinUiState(
        session = session.cleared(keepLoginForm).copy(errorMessage = errorMessage),
        library = library.cleared(),
        downloads = downloads.cleared(),
    )

    // ── 兼容层 · 旧字段写法（#11 加，#16 删）────────────────────────────
    // 与 data class 自动生成的 copy(session, library, downloads) 并存：参数名不重叠，
    // 旧的 `state.copy(tracks = ...)` 全部落到对应 slice 上。

    /**
     * 迁移期兼容写入口：参数名与旧顶层字段一一对应，值落到对应 slice。
     *
     * 这是**唯一**允许把旧字段名写进状态的地方。新增字段请直接加到 slice 上，
     * 不要再往这里加参数（#16 会整个删掉）。
     *
     * ⚠️ 两个 `copy` 的参数都是全默认值，所以**无参 `state.copy()` 会重载歧义编译失败**；
     * 需要空拷贝就直接复用实例。当前没有任何调用点，迁移时别新写。
     */
    fun copy(
        // 会话
        serverUrl: String = this.serverUrl,
        serverUrlHistory: List<String> = this.serverUrlHistory,
        username: String = this.username,
        password: String = this.password,
        isLoading: Boolean = this.isLoading,
        isRestoring: Boolean = this.isRestoring,
        errorMessage: String? = this.errorMessage,
        // 曲库
        tracks: List<Track>? = this.tracks,
        discoverAlbums: List<Album>? = this.discoverAlbums,
        recentlyPlayed: List<Track>? = this.recentlyPlayed,
        mostPlayed: List<Track>? = this.mostPlayed,
        recentlyAdded: List<Album>? = this.recentlyAdded,
        discoverFailedSections: Set<com.example.pulsar.state.LibrarySectionKey> = this.discoverFailedSections,
        historyEpoch: Int = this.historyEpoch,
        isDiscoverRefreshing: Boolean = this.isDiscoverRefreshing,
        albums: List<Album>? = this.albums,
        albumTracks: List<Track>? = this.albumTracks,
        selectedAlbum: Album? = this.selectedAlbum,
        playlists: List<Playlist>? = this.playlists,
        playlistTracks: List<Track>? = this.playlistTracks,
        selectedPlaylist: Playlist? = this.selectedPlaylist,
        multiSelectIds: Set<String> = this.multiSelectIds,
        isOffline: Boolean = this.isOffline,
        // 搜索
        searchQuery: String = this.searchQuery,
        searchResults: SearchResults? = this.searchResults,
        isSearching: Boolean = this.isSearching,
        isOfflineSearch: Boolean = this.isOfflineSearch,
        // 文件操作（含回收站）
        isFileOpsBusy: Boolean = this.isFileOpsBusy,
        fileOpsMessage: String? = this.fileOpsMessage,
        recycleFiles: List<com.example.pulsar.data.libraryfiles.LibraryRecycleFile> = this.recycleFiles,
        isRecycleLoading: Boolean = this.isRecycleLoading,
        recycleMessage: String? = this.recycleMessage,
        // 下载
        cachedTrackIds: Set<String>? = this.cachedTrackIds,
        cachedAlbums: List<CachedAlbum>? = this.cachedAlbums,
        isDeletingDownload: Boolean = this.isDeletingDownload,
        downloadDeleteMessage: String? = this.downloadDeleteMessage,
    ): JellyfinUiState = JellyfinUiState(
        session = session.copy(
            serverUrl = serverUrl,
            serverUrlHistory = serverUrlHistory,
            username = username,
            password = password,
            isLoading = isLoading,
            isRestoring = isRestoring,
            errorMessage = errorMessage,
        ),
        library = library.copy(
            tracks = tracks,
            discoverAlbums = discoverAlbums,
            recentlyPlayed = recentlyPlayed,
            mostPlayed = mostPlayed,
            recentlyAdded = recentlyAdded,
            discoverFailedSections = discoverFailedSections,
            historyEpoch = historyEpoch,
            isDiscoverRefreshing = isDiscoverRefreshing,
            albums = albums,
            albumTracks = albumTracks,
            selectedAlbum = selectedAlbum,
            playlists = playlists,
            playlistTracks = playlistTracks,
            selectedPlaylist = selectedPlaylist,
            multiSelectIds = multiSelectIds,
            isOffline = isOffline,
            search = library.search.copy(
                query = searchQuery,
                results = searchResults,
                isSearching = isSearching,
                isOfflineSearch = isOfflineSearch,
            ),
            fileOps = library.fileOps.copy(
                isBusy = isFileOpsBusy,
                message = fileOpsMessage,
                recycleFiles = recycleFiles,
                isRecycleLoading = isRecycleLoading,
                recycleMessage = recycleMessage,
            ),
        ),
        downloads = downloads.copy(
            visibleTrackIds = cachedTrackIds,
            cachedAlbums = cachedAlbums,
            isDeleting = isDeletingDownload,
            deleteMessage = downloadDeleteMessage,
        ),
    )
}
