package com.example.pulsar

import com.example.pulsar.domain.model.Track
import kotlin.random.Random

/**
 * 发现页「每日推荐」：从曲库里按 dayIndex（当天日期整数，如 20260903）稳定随机挑 count 首。
 * 同一天 + 同一批曲目 = 同一份歌单（跨重启/重组不变），换一天自动换一批。
 * variant 是刷新变体编号：下拉刷新一次 +1，同一编号内结果稳定，换编号即换一批。
 * 纯函数：不做任何 IO，UI 与测试都直接调用。
 */
internal fun discoverDailyPicks(
    tracks: List<Track>,
    dayIndex: Long,
    count: Int,
    variant: Int = 0
): List<Track> {
    if (tracks.isEmpty() || count <= 0) return emptyList()
    // 以日期 + 变体为种子的稳定洗牌：Random(seed) 保证同种子顺序一致，与曲目列表顺序解耦。
    val seed = (dayIndex.hashCode() * 31 + variant)
    val shuffled = tracks.shuffled(Random(seed))
    return shuffled.take(count)
}
