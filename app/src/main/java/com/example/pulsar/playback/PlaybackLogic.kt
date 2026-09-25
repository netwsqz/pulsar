package com.example.pulsar.playback

import com.example.pulsar.domain.model.Track
import kotlin.math.abs

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

/**
 * 松手 seek 后继续显示拖动值的兜底时长（毫秒）。
 *
 * 播放器位置正常会在下一次轮询（500ms）就追上目标；超过这个时长还没追上，说明 seek 根本没生效
 * （播放器已销毁 / 无媒体），此时必须放开，不能把进度条永久锁在拖动值上。
 */
const val SEEK_SETTLE_TIMEOUT_MS = 2_000L

/**
 * 松手 seek 之后，播放器上报的位置是否已经追上拖动到的目标位置。
 *
 * **为什么需要它**：`seekTo` 只更新播放器内部位置，喂给 UI 的 `positionMs` 要等下一次轮询
 * （本工程 500ms 一次）才会变。松手瞬间若立刻把显示值交还给播放器位置，进度条会先弹回
 * **拖动前**的旧位置、500ms 后再跳到新位置 —— 真机上就是「闪一下」。
 * 调用方据此在追上之前继续显示拖动值（并配 [SEEK_SETTLE_TIMEOUT_MS] 超时兜底）。
 *
 * [playerPositionSec] 或 [targetSec] 非有限值时一律返回 `true`（视为已追上）：宁可让显示值
 * 早点交还给播放器，也不要把 UI 锁在一个算不清的比较上。
 */
fun positionReachedSeekTarget(
    playerPositionSec: Float,
    targetSec: Float,
    toleranceSec: Float = 1f
): Boolean {
    if (!playerPositionSec.isFinite() || !targetSec.isFinite()) return true
    return abs(playerPositionSec - targetSec) <= toleranceSec
}