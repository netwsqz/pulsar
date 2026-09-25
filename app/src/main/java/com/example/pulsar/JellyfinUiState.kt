package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.SearchResults
import com.example.pulsar.domain.model.Track

data class JellyfinUiState(
    val serverUrl: String = "",
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
     * 有**完整副本**的曲目 id。null = 还不知道（在线时不需要知道，别标灰）。
     *
     * 只有完整副本才算能放：听到一半切走的半截副本离线时按「没缓存」处理。
     */
    val cachedTrackIds: Set<String>? = null,
    /** 当前搜索结果来自本地快照而不是 Jellyfin——界面要标明「离线搜索」，范围变小了。 */
    val isOfflineSearch: Boolean = false,
    /** 缓存占用字节数 / 完整副本首数（设置页缓存区块）。 */
    val cacheBytes: Long = 0,
    val cacheTrackCount: Int = 0,
    /** 清空缓存提交中：按钮据此防连点。 */
    val isCacheBusy: Boolean = false,
    /** 缓存区块的反馈（清空完成）。null = 没有提示。 */
    val cacheMessage: String? = null
)
