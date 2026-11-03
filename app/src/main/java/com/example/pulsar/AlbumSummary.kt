package com.example.pulsar

import com.example.pulsar.domain.model.Album
import com.example.pulsar.domain.model.Track

/**
 * 专辑详情头部的曲目摘要（工单 10）。与播放列表的 [playlistSummary] 同一口径：
 *
 * 计数必须和列表实际显示的行数同源——曲目已加载时用 [tracks] 的长度，只有没加载（骨架期）
 * 时才退回服务端的 [Album.trackCount]。离线视图按可见曲目（已下载的）计数，并给出服务端总数，
 * 写成「已下载 N 首 · 共 M 首」。
 *
 * 在线摘要额外带时长（「N 首 · M 分钟」）；离线摘要只讲下载数，不混时长。
 */
internal fun albumSummary(
    album: Album,
    tracks: List<Track>?,
    isOfflineView: Boolean
): String {
    // 曲目还没加载（离线读本地清单 / 在线拉取中）：退回服务端计数，别闪「已下载 0 首」。
    if (tracks == null) return album.trackCount?.let { "$it 首" } ?: "专辑"
    val visible = tracks.size
    // 离线摘要复用播放列表那一份：「已下载 N 首 · 共 M 首」，总数同样不能小于可见行数。
    if (isOfflineView) return downloadedTrackSummary(visible, album.trackCount)
    val minutes = tracks.sumOf { it.durationMs ?: 0L } / 60_000
    return if (minutes > 0) "$visible 首 · $minutes 分钟" else "$visible 首"
}
