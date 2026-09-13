package com.example.pulsar.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 播放输出诊断信息，供 UI 展示"声音走哪个设备、什么采样率、什么位深"。
 *
 * - [viaUsb]：当前是否已把播放器路由到 USB 输出设备（小尾巴/DAC）。
 * - [sampleRateHz]：音频管线实际采用的采样率（44.1k = 44100）。这是
 *   源文件的原生采样率，不是 DAC 晶振的实际频率——Android 不开放读取
 *   后者，对 44.1k 内容显示 44100Hz 即可合理确认没有被系统压成 48k。
 * - [encoding]：**源的真实位深**——解码器实际输出的 PCM 编码（[androidx.media3.common.C]
 *   的 ENCODING_PCM_*）。刻意不取 AudioTrack 的编码（系统默认输出会被统一成
 *   float32）也不取 USB 线宽（独占路径按 32bit 子帧送，那只是容器宽度）：
 *   UI 要让用户确认的正是"16bit 源有没有被截、24bit 源是不是真的到 24bit"。
 */
data class PlaybackOutputInfo(
    val viaUsb: Boolean = false,
    val sampleRateHz: Int? = null,
    val encoding: Int? = null
)

/**
 * 解码器输出的 pcmEncoding 能否作为 UI 的位深展示。
 *
 * 只放行真实存在的 PCM 编码；压缩格式（解码器输入侧）没有 pcmEncoding，Media3 给
 * [Format.NO_VALUE]/[C.ENCODING_INVALID]，直接写进去 UI 会显示「编码-1」。
 */
internal fun decoderEncodingForDiagnostics(pcmEncoding: Int): Int? =
    pcmEncoding.takeIf { it != Format.NO_VALUE && it != C.ENCODING_INVALID }

/** 播放服务写入、UI 读取的进程级单例（服务可能比 Activity 活得久）。 */
object PlaybackDiagnostics {
    val info: MutableStateFlow<PlaybackOutputInfo> = MutableStateFlow(PlaybackOutputInfo())
}
