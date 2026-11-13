package com.example.pulsar.search

import com.example.pulsar.QQDownloadUiState
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.data.qqdownload.QQAlbumDto
import com.example.pulsar.data.qqdownload.QQSongDto
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track
import com.example.pulsar.offline.OfflineSearchNotice
import com.example.pulsar.searchStatus
import com.example.pulsar.state.SearchState

/**
 * 搜索页的渲染输入（`docs/unified-search-refactor.md` Step 2–3 / #18）。
 *
 * [view] 是纯函数 [unifiedSearchViewState] 的结论，页面只按它的类型渲染——合并规矩不再
 * 在 Composable 里重复一遍。其余字段是渲染旁路，不属于合并规则，所以不进 [SearchViewState]：
 * 输入框需要即时值、曲目行要高亮当前播放、封面请求要鉴权、QQ 分区与弹窗要下载状态。
 */
internal data class SearchScreenState(
    val view: SearchViewState,
    /** 输入框即时值（防抖前的原始输入）。 */
    val query: String,
    /** 「离线搜索」标识，渲染在搜索框下方；与内容分支无关（Loading 时也该在）。 */
    val searchNotice: OfflineSearchNotice?,
    /** QQ 下载侧状态：搜索结果里的下载标记、专辑入队进度、两个弹窗的数据源。 */
    val qq: QQDownloadUiState,
    val currentTrackId: String?,
    val isPlaying: Boolean,
    val trackCover: (Track) -> JellyfinRequest?,
    val albumCover: (Album) -> JellyfinRequest?,
    val playlistCover: (Playlist) -> JellyfinRequest?
)

/**
 * 把调用点的原始状态摆成 [SearchScreenState]：合并结论交给 [unifiedSearchViewState]，
 * 其余字段原样带上。
 *
 * 这里只做搬运、不做判断——合并规则只有 [unifiedSearchViewState] 一处。放在纯函数里是为了
 * 调用点不再拼输入，也让「离线标识在 Loading 时仍然要在」这类旁路口径能用普通单测钉住。
 */
internal fun searchScreenState(
    search: SearchState,
    results: SearchResults?,
    errorMessage: String?,
    searchNotice: OfflineSearchNotice?,
    qq: QQDownloadUiState,
    currentTrackId: String?,
    isPlaying: Boolean,
    trackCover: (Track) -> JellyfinRequest?,
    albumCover: (Album) -> JellyfinRequest?,
    playlistCover: (Playlist) -> JellyfinRequest?
): SearchScreenState = SearchScreenState(
    view = unifiedSearchViewState(
        input = SearchInput(
            query = search.query,
            results = results,
            isSearching = search.isSearching,
            errorMessage = errorMessage,
            searchNotice = searchNotice
        ),
        qq = QqSearchInput(
            songs = qq.songs,
            albums = qq.albums,
            isSearching = qq.isSearching,
            searchStatus = qq.searchStatus
        )
    ),
    query = search.query,
    searchNotice = searchNotice,
    qq = qq,
    currentTrackId = currentTrackId,
    isPlaying = isPlaying,
    trackCover = trackCover,
    albumCover = albumCover,
    playlistCover = playlistCover
)

/**
 * 搜索页的全部回调（Step 3）。
 *
 * 改前 `SearchContent` 有 30 个参数、`QQSearchDialogs` 有 10 个，调用点一行一个
 * `viewModel::*` / `qqViewModel::*` 地转发；现在收成一个值对象，页面和弹窗都只认「能做什么」，
 * 不关心它们来自哪个 ViewModel。`onQueryChange` 仍然一次喂给两个 ViewModel（ADR-0006：
 * 两个防抖各跑各的，这里不做聚合）。
 */
internal data class SearchActions(
    /** 输入框变化：同时通知曲库与 QQ 两个 ViewModel。 */
    val onQueryChange: (String) -> Unit,
    /** 曲库搜索失败后的整页重试。 */
    val onRetryLibrary: () -> Unit,
    /** QQ 搜索失败（连不上 / 凭证过期）后的分区重试。 */
    val onRetryQqSearch: () -> Unit,
    val onRetryCredential: () -> Unit,
    val onPlayTrack: (List<Track>, String) -> Unit,
    val onAddToQueue: (Track) -> Unit,
    val onOpenAlbum: (Album) -> Unit,
    val onOpenPlaylist: (Playlist) -> Unit,
    val onDownloadSong: (QQSongDto) -> Unit,
    val onRequestRedownload: (QQSongDto) -> Unit,
    val onDownloadAlbum: (QQAlbumDto) -> Unit,
    val onSelectAlbumTracks: (QQAlbumDto) -> Unit,
    // ── 两个弹窗（重下确认 / 专辑选曲）──────────────────────────────────
    val onConfirmRedownload: () -> Unit,
    val onDismissRedownload: () -> Unit,
    val onToggleAlbumTrack: (String) -> Unit,
    val onSelectAllAlbumTracks: () -> Unit,
    val onClearAlbumSelection: () -> Unit,
    val onConfirmAlbumDownload: () -> Unit,
    val onRetryAlbumTracks: () -> Unit,
    val onDismissAlbumPicker: () -> Unit
)
