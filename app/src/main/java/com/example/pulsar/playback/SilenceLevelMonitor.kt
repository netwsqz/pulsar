package com.example.pulsar.playback

/**
 * 音频 sink（播放线程）写入、播放服务（轮询协程）读取的电平持有者。
 *
 * [SilenceSkippingAudioSink.handleBuffer] 每次写入 PCM 数据时更新 [levelDb]；
 * [com.example.pulsar.PlaybackService] 定时轮询该值并结合播放位置/时长交给
 * [SilenceSkipDetector] 判定是否应跳到下一曲。两者在不同线程访问，故用 [Volatile]。
 */
class SilenceLevelMonitor {
    @Volatile
    var levelDb: Double = SILENT_FLOOR_DB
}
