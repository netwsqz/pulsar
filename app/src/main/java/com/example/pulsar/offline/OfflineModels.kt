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
    /** 这轮曲库搜索结果是否来自本地快照（`JellyfinUiState.isOfflineSearch`）。 */
    val isOfflineSearch: Boolean = false,
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
    val emptyKind: DiscoverEmpty,
    /** 搜索页的离线标识；null = 在线搜索，不打标。 */
    val searchNotice: OfflineSearchNotice?
)

/**
 * 页面标题下那行离线小字。文案见 [message]，**全 App 只有这一处定义**（#10）。
 */
internal enum class OfflineNotice {
    /** 离线 · 只显示已下载 */
    VisibleOnly
}

/** 离线提示文案的唯一出处：界面按类型取值，不再自己写字面量。 */
internal fun OfflineNotice.message(): String = when (this) {
    OfflineNotice.VisibleOnly -> "离线 · 只显示已下载"
}

/**
 * 离线空态：列表已经被离线策略筛过，但确实没有可显示的内容。
 *
 * 只在 `isFiltering == true` 时使用。在线空态是另一套文案（「暂无专辑」「暂无音乐」…），
 * 不在这里；离线空态与在线空态不能互相顶替——用户要能分清「曲库空了」和「离线只能看下载」。
 */
internal enum class OfflineEmpty {
    /** 离线 · 手机里还没有下载的专辑 */
    Albums,

    /** 离线 · 手机里还没有下载的歌曲 */
    Tracks
}

/** 离线空态文案的唯一出处。 */
internal fun OfflineEmpty.message(): String = when (this) {
    OfflineEmpty.Albums -> "离线 · 手机里还没有下载的专辑"
    OfflineEmpty.Tracks -> "离线 · 手机里还没有下载的歌曲"
}

/**
 * 搜索页的离线标识。只描述曲库那一半——QQ 音乐是另一个服务，离线也照常能搜（ADR-0006）。
 */
internal enum class OfflineSearchNotice {
    /** 离线搜索且列表已按已下载集合筛过：范围 = 下载过的歌。 */
    DownloadedOnly,

    /** 离线搜索但已下载集合还没算出来（列表没筛）：范围 = 手机上的快照。 */
    SnapshotOnly
}

/** 离线搜索提示文案的唯一出处。 */
internal fun OfflineSearchNotice.message(): String = when (this) {
    OfflineSearchNotice.DownloadedOnly -> "曲库离线 · 只搜已下载；QQ 音乐不受影响"
    OfflineSearchNotice.SnapshotOnly -> "曲库离线 · 只搜手机上的快照；QQ 音乐不受影响"
}

/**
 * 搜索页该不该打离线标识、打哪一句；null = 不打（在线搜索）。
 *
 * [isFiltering] 必须来自 [OfflineView.isFiltering]（离线且已下载集合已知），
 * 界面不要自己拼 `isOffline && downloadedTrackIds != null`。
 */
internal fun offlineSearchNotice(isOfflineSearch: Boolean, isFiltering: Boolean): OfflineSearchNotice? =
    when {
        !isOfflineSearch -> null
        isFiltering -> OfflineSearchNotice.DownloadedOnly
        else -> OfflineSearchNotice.SnapshotOnly
    }
