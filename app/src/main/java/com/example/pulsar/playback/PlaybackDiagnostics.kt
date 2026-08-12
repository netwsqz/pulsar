package com.example.pulsar.playback

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 播放输出诊断信息，供 UI 展示"声音走哪个设备、什么采样率、什么位深"。
 *
 * - [viaUsb]：当前是否已把播放器路由到 USB 输出设备（小尾巴/DAC）。
 * - [sampleRateHz]：音频管线实际采用的采样率（44.1k = 44100）。这是
 *   源文件的原生采样率，不是 DAC 晶振的实际频率——Android 不开放读取
 *   后者，对 44.1k 内容显示 44100Hz 即可合理确认没有被系统压成 48k。
 * - [encoding]：音频管线实际写出的编码（[androidx.media3.common.C] 的
 *   ENCODING_PCM_*）。float32 说明 24-bit 源未被截断成 16-bit。
 */
data class PlaybackOutputInfo(
    val viaUsb: Boolean = false,
    val sampleRateHz: Int? = null,
    val encoding: Int? = null
)

/** 播放服务写入、UI 读取的进程级单例（服务可能比 Activity 活得久）。 */
object PlaybackDiagnostics {
    val info: MutableStateFlow<PlaybackOutputInfo> = MutableStateFlow(PlaybackOutputInfo())
}
