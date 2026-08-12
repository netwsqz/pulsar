package com.example.pulsar.playback

import com.example.pulsar.domain.model.Track

/**
 * 构建播放队列：将 [tracks] 旋转，使 [startId] 对应的曲目位于队列开头。
 *
 * 如果 [startId] 不在列表中或列表为空，返回 null。
 */
fun buildPlaybackOrder(tracks: List<Track>, startId: String): List<Track>? {
    val index = tracks.indexOfFirst { it.id == startId }.takeIf { it >= 0 } ?: return null
    return tracks.subList(index, tracks.size) + tracks.subList(0, index)
}

/**
 * 将毫秒数格式化为 `m:ss` 格式。
 *
 * 负值或无法确定的值统一返回 `"0:00"`。
 */
fun formatPosition(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:%02d".format(seconds)
}