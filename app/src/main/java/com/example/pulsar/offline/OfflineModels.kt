package com.example.pulsar.offline

import com.example.pulsar.DiscoverEmpty
import com.example.pulsar.DiscoverSections
import com.example.pulsar.VisibleLibrary
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track

/**
 * 离线策略的输入：各屏原始状态 + 「离线时能看见/能播」的那份判据。
 *
 * 全部字段都是界面本来就拿得到的东西——策略不持有状态、不做 IO、不查下载字节
 * （[downloadedTrackIds] 由 ViewModel 算好传进来，ADR-0008 / ADR-0007）。
 * **原始 state 一律不动**：这里只是派生一份渲染用的视图。
 */
internal data class OfflineInput(
    val isOffline: Boolean,
    /** 离线时能显示也能播的曲目 id。null = 还没算出来（不筛）；空集 = 确实一首都没下载。 */
    val downloadedTrackIds: Set<String>?,
    val tracks: List<Track>?,
    val albums: List<Album>?,
    val discoverAlbums: List<Album>?,
    val albumTracks: List<Track>?,
    val playlistTracks: List<Track>?,
    val searchResults: SearchResults?,
    /** 三节服务器历史（离线时整节不画，但输入照传——数据本来就在 state 里）。 */
    val recentlyPlayed: List<Track>? = null,
    val mostPlayed: List<Track>? = null,
    val recentlyAdded: List<Album>? = null,
    /** 曲库是否已经拉到（即 `tracks != null`）：null 与空列表语义不同——前者是骨架，后者确实没有。 */
    val tracksLoaded: Boolean,
    val isLoading: Boolean
)

/**
 * 离线策略的输出：界面只渲染这一份，不再自己组合 `isOffline && cachedTrackIds != null`。
 *
 * [visibleLibrary] 的 null / 空列表语义与原始列表一致：null = 没拉到（骨架），
 * 空列表 = 拉到了但确实没有（空态）。
 */
internal data class OfflineView(
    val visibleLibrary: VisibleLibrary,
    /** true = 列表真的被筛过（等价于旧的 offlineFilter）；文案与下载按钮显隐都看它。 */
    val isFiltering: Boolean,
    /** 标题下那行离线小字；null = 不提示。 */
    val notice: OfflineNotice?,
    /** 发现页「哪几节显示」——三节服务器历史离线时整节不显示（spec §7）。 */
    val discover: DiscoverSections,
    /** 发现页空态该说哪句话（在线 = 曲库为空，离线 = 还没下载）。 */
    val emptyKind: DiscoverEmpty
)

/** 离线提示的类型。文案收口在 #10，这里只定「提示哪种」。 */
internal enum class OfflineNotice {
    /** 离线 · 只显示已下载 */
    VisibleOnly
}
