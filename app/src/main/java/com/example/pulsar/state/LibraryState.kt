package com.example.pulsar.state

import com.example.pulsar.data.libraryfiles.LibraryRecycleFile
import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track

/**
 * 曲库 slice（`docs/library-state-refactor.md` Step 1 / #11）：曲库、发现、详情、搜索、多选、
 * 文件操作与离线可见性。
 *
 * 拆分口径是**所有权**，不是「谁在哪个页面用」：
 * - 服务端拉来的内容 + 浏览位置 → 直接放这里；
 * - 搜索自己的一组状态 → [SearchState]；
 * - PC 伴生服务的界面状态（含回收站）→ [FileOpsState]；
 * - 下载派生（已下载集合、已下载专辑）→ [DownloadsState]，不在这里再存一份。
 *
 * 登出 / 会话过期会清掉这一块的大部分（#13 会把清理语义收进来）。**注意现状**：
 * `signOut` 重建整份 state（全清），而 `expireSession` 不清 [historyEpoch] 与 [FileOpsState]
 * 的回收站字段——与改前一致，#13 收口时再统一。**离线字段也在这里**：
 * [isOffline] 说的是「曲库这份数据来自快照还是服务器」，不是会话凭据状态。
 */
data class LibraryState(
    val tracks: List<Track>? = null,
    val albums: List<Album>? = null,
    val discoverAlbums: List<Album>? = null,
    val albumTracks: List<Track>? = null,
    /**
     * 详情页当前选中的专辑 / 播放列表。**没有生产 UI 读取**（导航真相是
     * `LibrarySection.AlbumDetail(album)`），只用于幂等守卫（同 id 不重复拉）。
     * #16 会删掉这两个字段。
     */
    val selectedAlbum: Album? = null,
    val playlists: List<Playlist>? = null,
    val playlistTracks: List<Track>? = null,
    val selectedPlaylist: Playlist? = null,
    /**
     * 发现页个人化聚合（工单 02）。null = 还没成功拉到（含上次失败，进页会重试）；
     * 空列表 = 拉到了但服务端没有数据（没播过歌），界面据此隐藏该 section。
     */
    val recentlyPlayed: List<Track>? = null,
    val mostPlayed: List<Track>? = null,
    val recentlyAdded: List<Album>? = null,
    /**
     * 三节历史里**已经失败过**的那些节（枚举，不再用字符串）。
     *
     * 失败的那节字段保持 null，界面据此**不再为它显示骨架**（否则永远转圈），只让这一节缺席；
     * 其余 section 照常显示。单节失败不打扰用户，三节全失败才给一次错误 + 重试。
     * 下一次真正发起请求时把对应项移出（重试能看到骨架）。
     */
    val discoverFailedSections: Set<LibrarySectionKey> = emptySet(),
    /**
     * 播放历史「版本号」：切歌（服务端 UserData 变了）或点「换一批」刷新时递增。
     *
     * **过期不等于清空**：数据仍留在 [recentlyPlayed] / [mostPlayed] / [recentlyAdded] 里
     * 照常显示，后台静默重拉成功后原地替换。清成 null 会让发现页横滑先闪成骨架再弹回来。
     * 界面把它当 LaunchedEffect 的 key 触发重拉；每节自己记住已拉到的版本，相等就不再请求。
     */
    val historyEpoch: Int = 0,
    /** 发现页刷新进行中（顶部「换一批」按钮转圈用）。与 `isLoading` 分开：进页时的常规加载不该转这个圈。 */
    val isDiscoverRefreshing: Boolean = false,
    val search: SearchState = SearchState(),
    /**
     * 多选模式的已选曲目 id 集合（工单 05）。**空 = 非多选模式**；
     * 非空时 TrackRow 点击 = 勾选、顶部出现批量操作条。
     */
    val multiSelectIds: Set<String> = emptySet(),
    val fileOps: FileOpsState = FileOpsState(),
    /**
     * 离线态：连不上 Jellyfin，列表来自曲库快照。在线一律 false。
     *
     * 恢复联网后不用用户做任何操作，`JellyfinViewModel` 自己的探活会把它翻回 false
     * （用户故事 13）。全局共用：任意网络请求的 `Network` 错误都会触发它（ADR-0008）。
     */
    val isOffline: Boolean = false,
)

/**
 * 发现页三节服务器历史的标识（`docs/library-state-refactor.md` Step 2 / #12）：
 * 失败标记、版本号比对都按它记，不再用字符串字面量。
 */
enum class LibrarySectionKey {
    RecentlyPlayed,
    MostPlayed,
    RecentlyAdded,
}

/** 搜索的一整组状态（原 `searchQuery` / `searchResults` / `isSearching` / `isOfflineSearch`）。 */
data class SearchState(
    val query: String = "",
    val results: com.example.pulsar.domain.model.SearchResults? = null,
    val isSearching: Boolean = false,
    /** 当前搜索结果来自本地快照而不是 Jellyfin——界面要标明「离线搜索」，范围变小了。 */
    val isOfflineSearch: Boolean = false,
)

/**
 * 曲库文件操作（PC 伴生服务，ADR-0001）的界面状态：单次删除/改名的忙碌与提示，加上回收站。
 *
 * 两者都走同一个伴生服务、都在设置页/详情页出现，所以收在一块；`isBusy` 与回收站共用
 * （恢复/清空也算文件操作，互斥防连点）。
 */
data class FileOpsState(
    /** 文件操作（删除/改名/恢复/清空）提交中：对话框据此禁按钮防误关。 */
    val isBusy: Boolean = false,
    /** 最近一次文件操作的失败原因；发起新操作或成功时清空。null = 没有失败提示。 */
    val message: String? = null,
    /** 回收站内容（设置页用），按删除时间倒序。空列表 = 回收站是空的。 */
    val recycleFiles: List<LibraryRecycleFile> = emptyList(),
    val isRecycleLoading: Boolean = false,
    /** 回收站操作的反馈（加载失败 / 恢复成功 / 清空成功共用一格）。 */
    val recycleMessage: String? = null,
)
