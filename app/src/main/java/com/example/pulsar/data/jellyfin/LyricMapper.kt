package com.example.pulsar.data.jellyfin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jellyfin 歌词接口 DTO（GET /Audio/{itemId}/Lyrics，Jellyfin 10.9+）。
 *
 * 服务器会把 .lrc 文件解析成带时间戳的行；纯文本 .txt 歌词所有行 Start=0。
 * Start 单位是 Jellyfin ticks（100 纳秒），10_000_000 ticks = 1 秒。
 */
@Serializable
data class LyricResponseDto(
    @SerialName("Lyrics") val lyrics: List<LyricLineDto> = emptyList()
)

@Serializable
data class LyricLineDto(
    @SerialName("Text") val text: String = "",
    @SerialName("Start") val start: Long = 0L
)

/**
 * 全屏播放页歌词页的展示模型：行列表 + 是否逐行同步。
 *
 * - [isSynced]=true：逐行滚动 + 高亮当前行（currentIndexFor 定位）。
 * - [isSynced]=false：静态展示全部行，不滚动。
 * - [lines] 为空：曲库没有歌词，显示引导空态。
 */
data class Lyrics(
    val isSynced: Boolean,
    /** 已按时间升序排列；纯文本歌词保持服务器返回顺序。 */
    val lines: List<LyricLine>?,
) {
    /**
     * 按播放位置定位当前应高亮的行下标。
     * 规则：位置 ≥ 某行起点即属于该行（两行之间取较早的行）；首行之前返回 -1（无高亮）。
     * 纯文本 / 空歌词不定位，恒返回 -1。
     * 二分查找：歌词通常几十到几百行，逐行线性扫也够快，但切歌瞬间 positionMs
     * 轮询（500ms）每次都会调它，二分把最坏 O(n) 收敛到 O(log n)，顺带免掉大 LRC 的抖动。
     */
    fun currentIndexFor(positionUs: Long): Int {
        if (!isSynced || lines.isNullOrEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].startUs <= positionUs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /** 无歌词哨兵：lines=null 与「空响应」区分，UI 据此显示不同空态。 */
    companion object {
        val NONE = Lyrics(isSynced = false, lines = null)
    }
}

/** 单行歌词的展示时间（微秒，与播放位置同单位，避免每次比较都换算）。 */
data class LyricLine(
    val startUs: Long,
    val text: String,
)

/** ===== DTO → 展示模型转换（纯函数，UI 侧不接触 DTO） ===== */

/** Jellyfin ticks → 微秒：ticks / 10（10_000_000 ticks/秒 ÷ 1_000_000 µs/秒）。 */
internal fun ticksToUs(ticks: Long): Long = ticks / 10

/**
 * 是否为逐行同步歌词：首行以外的任意行 Start>0 即同步。
 * 纯文本歌词服务器返回所有行 Start=0；LRC 首行也常是 0，不能只看首行。
 */
internal fun isSyncedLyrics(lines: List<LyricLineDto>): Boolean =
    lines.any { it.start > 0L }

/** 转 [Lyrics]：同步歌词按 Start 升序排列（服务器偶发乱序时保底）。 */
fun LyricResponseDto.toLyrics(): Lyrics {
    if (lyrics.isEmpty()) return Lyrics(isSynced = false, lines = emptyList())
    val synced = isSyncedLyrics(lyrics)
    val lines = lyrics
        .map { LyricLine(startUs = ticksToUs(it.start), text = it.text) }
        .let { if (synced) it.sortedBy(LyricLine::startUs) else it }
    return Lyrics(isSynced = synced, lines = lines)
}
