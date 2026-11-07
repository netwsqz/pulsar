package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track

data class JellyfinUiState(
    val serverUrl: String = "",
    /** 最近登录成功的服务器地址（最新在前）；登录页输入框获焦时下拉可选。 */
    val serverUrlHistory: List<String> = emptyList(),
    val username: String = "",
    val password: String = "",
    /** 登录/手动加载中（按钮转圈）。 */
    val isLoading: Boolean = false,
    /**
     * 启动时正在恢复已保存的会话（读本地 token → 拉曲库）。
     *
     * 与 [isLoading] 分开：恢复是应用自己发起的、用户无需交互。为 true 时 UI **不**回到
     * 登录表单，而是直接进主页面、用灰色波浪骨架占位等着——只有恢复结束且 [tracks] 仍为
     * null（本地没存过、或恢复失败）才判定"需要登录"。
     */
    val isRestoring: Boolean = false,
    val errorMessage: String? = null,
    val tracks: List<Track>? = null,
    val discoverAlbums: List<Album>? = null,
    /**
     * 发现页个人化聚合（工单 02）。null = 还没成功拉到（含上次失败，进页会重试）；
     * 空列表 = 拉到了但服务端没有数据（没播过歌），界面据此隐藏该 section。
     */
    val recentlyPlayed: List<Track>? = null,
    val mostPlayed: List<Track>? = null,
    val recentlyAdded: List<Album>? = null,
    /**
     * 三节历史里**已经失败过**的那些节（按 section 名记，如 `"recentlyPlayed"`）。
     *
     * 失败的那节字段保持 null，界面据此**不再为它显示骨架**（否则永远转圈），只让这一节缺席；
     * 其余 section 照常显示。单节失败不打扰用户，三节全失败才给一次错误 + 重试。
     * 下一次真正发起请求时把对应项移出（重试能看到骨架）。
     */
    val discoverFailedSections: Set<String> = emptySet(),
    /**
     * 播放历史「版本号」：切歌（服务端 UserData 变了）或点「换一批」刷新时递增。
     *
     * **过期不等于清空**：数据仍留在 [recentlyPlayed] / [mostPlayed] / [recentlyAdded] 里
     * 照常显示，后台静默重拉成功后原地替换。清成 null 会让发现页横滑先闪成骨架再弹回来。
     * 界面把它当 LaunchedEffect 的 key 触发重拉；每节自己记住已拉到的版本，相等就不再请求。
     */
    val historyEpoch: Int = 0,
    /** 发现页刷新进行中（顶部「换一批」按钮转圈用）。与 [isLoading] 分开：进页时的常规加载不该转这个圈。 */
    val isDiscoverRefreshing: Boolean = false,
    val albums: List<Album>? = null,
    val albumTracks: List<Track>? = null,
    val selectedAlbum: Album? = null,
    val playlists: List<Playlist>? = null,
    val playlistTracks: List<Track>? = null,
    val selectedPlaylist: Playlist? = null,
    val searchQuery: String = "",
    val searchResults: SearchResults? = null,
    val isSearching: Boolean = false,
    /** 文件操作（删除/改名）提交中：对话框据此禁按钮防误关。 */
    val isFileOpsBusy: Boolean = false,
    /** 最近一次文件操作的失败原因；发起新操作或成功时清空。null = 没有失败提示。 */
    val fileOpsMessage: String? = null,
    /** 回收站内容（设置页用），按删除时间倒序。空列表 = 回收站是空的。 */
    val recycleFiles: List<com.example.pulsar.data.libraryfiles.LibraryRecycleFile> = emptyList(),
    val isRecycleLoading: Boolean = false,
    /** 回收站操作的反馈（加载失败 / 恢复成功 / 清空成功共用一格）。 */
    val recycleMessage: String? = null,
    /**
     * 多选模式的已选曲目 id 集合（工单 05）。**空 = 非多选模式**；
     * 非空时 TrackRow 点击 = 勾选、顶部出现批量操作条。
     */
    val multiSelectIds: Set<String> = emptySet(),
    /**
     * 离线态：连不上 Jellyfin，列表来自曲库快照。在线一律 false。
     *
     * 恢复联网后不用用户做任何操作，[JellyfinViewModel] 自己的探活会把它翻回 false
     * （用户故事 13）。
     */
    val isOffline: Boolean = false,
    /**
     * 离线时**能显示也能播**的曲目 id（已下载，ADR-0008）。
     * null = 还不知道（在线时不需要知道，界面不筛）。
     *
     * 口径是**交集**：字节完整（`MediaDownloads.completeKeys`）**且**在当前账号的下载
     * 名单里（换账号看不见别人的下载）。半截的不算，听到一半切走的不算。
     */
    val cachedTrackIds: Set<String>? = null,
    /** 当前搜索结果来自本地快照而不是 Jellyfin——界面要标明「离线搜索」，范围变小了。 */
    val isOfflineSearch: Boolean = false,
    /** 已下载列表（设置页进）：按专辑分组的完整副本进度。null = 还没进过这页。 */
    val cachedAlbums: List<CachedAlbum>? = null,
    /** 下载占用字节数 / 完整副本首数（设置页「下载」区块）。 */
    val cacheBytes: Long = 0,
    val cacheTrackCount: Int = 0,
    /**
     * 整张专辑的下载正在删（工单 13）。已下载列表的确认框据此显示进度、禁用按钮，
     * 删完落回 false 才关框——删除跑在 [JellyfinViewModel] 里，页面切走也不中断。
     */
    val isDeletingDownload: Boolean = false,
    /**
     * 下载删除完成后的提示（「已删除 N 首」，用户故事 62）。界面弹完调
     * [JellyfinViewModel.dismissDownloadDeleteMessage] 清掉，避免转屏/返回后再弹一次。
     */
    val downloadDeleteMessage: String? = null
)
