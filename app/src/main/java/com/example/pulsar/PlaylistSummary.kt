package com.example.pulsar

import com.example.pulsar.domain.model.Playlist
import com.example.pulsar.domain.model.Track

/**
 * 离线视图的摘要文案，专辑详情、播放列表详情与艺术家组详情共用（工单 10 / 11）：写成
 * 「已下载 N <单位> · 共 M <单位>」。[unit] 是计量单位——曲目用「首」（默认），艺术家组用「张」。
 *
 * [visible] 是列表实际显示的行数 / 卡片数；[serverTotal] 是服务端给的总数。服务端计数可能滞后
 * （比已下载的还少）：总数不能小于实际可见数，否则会出现「已下载 4 首 · 共 3 首」这种对不上的
 * 摘要。服务端没给总数时就按可见数算。
 */
internal fun downloadedTrackSummary(visible: Int, serverTotal: Int?, unit: String = "首"): String {
    val total = serverTotal?.coerceAtLeast(visible) ?: visible
    return "已下载 $visible $unit · 共 $total $unit"
}

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
    return downloadedTrackSummary(visible, playlist.trackCount)
}
