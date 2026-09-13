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
    val isSearching: Boolean = false
)
