package com.example.pulsar

import com.example.pulsar.domain.model.Track

/**
 * 多选「全选」的目标列表：**按当前页面取可见列表**（#30）。
 *
 * 入参是 [VisibleLibrary]（已过离线策略，ADR-0008）而不是原始 state——离线时界面只显示
 * 已下载的歌，全选若取全量就会把看不见的歌勾进删除集合。
 * 设置 / 下载等没有曲目行的页面返回空列表。
 */
internal fun currentListTracks(section: LibrarySection, visible: VisibleLibrary): List<Track> =
    when (section) {
        LibrarySection.Tracks, LibrarySection.Discover -> visible.tracks.orEmpty()
        is LibrarySection.AlbumDetail -> visible.albumTracks.orEmpty()
        is LibrarySection.PlaylistDetail -> visible.playlistTracks.orEmpty()
        LibrarySection.Search -> visible.searchResults?.tracks.orEmpty()
        else -> emptyList()
    }
