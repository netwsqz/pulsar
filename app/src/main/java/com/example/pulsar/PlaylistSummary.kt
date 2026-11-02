package com.example.pulsar

import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track

/**
 * 播放列表详情头部的曲目摘要（工单 09）。
 *
 * 计数必须和列表实际显示的行数同源：曲目已加载时用 [tracks] 的长度，只有没加载时才退回
 * 服务端的 [Playlist.trackCount]。离线视图按可见曲目（已下载且仍在列表里的）计数，并给出
 * 服务端总数，写成「已下载 N 首 · 共 M 首」。
 */
internal fun playlistSummary(
    playlist: Playlist,
    tracks: List<Track>?,
    isOfflineView: Boolean
): String {
    // 曲目还没加载（离线读本地清单 / 在线拉取中）：退回服务端计数。
    // 这时的列表还是骨架，不能写「已下载 0 首」——离线打开已下载列表会先闪一下 0。
    if (tracks == null) return playlist.trackCount?.let { "$it 首" } ?: "播放列表"
    val visible = tracks.size
    if (!isOfflineView) return "$visible 首"
    // 服务端计数可能滞后（比已下载的还少）：总数不能小于实际显示行数。
    val total = playlist.trackCount?.coerceAtLeast(visible) ?: visible
    return "已下载 $visible 首 · 共 $total 首"
}
