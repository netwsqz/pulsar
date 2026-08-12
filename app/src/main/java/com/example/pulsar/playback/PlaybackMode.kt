package com.example.pulsar.playback

import androidx.media3.common.Player

/**
 * 播放模式：顺序循环、单曲循环、随机播放。
 */
enum class PlaybackMode(val label: String) {
    SEQUENCE("顺序循环"),
    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放");

    /** 切换到下一种模式（顺序 → 单曲 → 随机 → 顺序…）。 */
    fun next(): PlaybackMode =
        when (this) {
            SEQUENCE -> REPEAT_ONE
            REPEAT_ONE -> SHUFFLE
            SHUFFLE -> SEQUENCE
        }
}

/** 播放模式对应的 Media3 播放器设置。 */
data class PlaybackModeConfig(val repeatMode: Int, val shuffleEnabled: Boolean)

/** 把 [mode] 翻译成播放器的 repeatMode 与 shuffle 开关。 */
fun playbackModeConfig(mode: PlaybackMode): PlaybackModeConfig =
    when (mode) {
        PlaybackMode.SEQUENCE -> PlaybackModeConfig(Player.REPEAT_MODE_ALL, shuffleEnabled = false)
        PlaybackMode.REPEAT_ONE -> PlaybackModeConfig(Player.REPEAT_MODE_ONE, shuffleEnabled = false)
        PlaybackMode.SHUFFLE -> PlaybackModeConfig(Player.REPEAT_MODE_ALL, shuffleEnabled = true)
    }