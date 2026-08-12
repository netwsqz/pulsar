package com.example.pulsar.domain.model

/**
 * 搜索结果：一次请求查询歌曲 / 专辑 / 播放列表三类，按 [tracks] / [albums] / [playlists] 分组。
 */
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList()
)
