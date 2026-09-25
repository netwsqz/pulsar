package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 原样播放的增益系数（不增不减）。 */
const val UNITY_GAIN = 1f

/**
 * 播放条目元数据 extras 里携带响度增益（dB）的键。
 *
 * 用 extras 而不是 MediaItem 的本地 tag：队列是从界面经 MediaController 交给播放服务的，
 * tag 只存在于控制端本地，到不了服务端；extras 会随 MediaMetadata 一起传过去。
 * 读写一律走 [putLoudnessGain] / [loudnessGainDb]，避免两边对键名的理解跑偏。
 */
const val LOUDNESS_GAIN_DB_KEY = "com.example.pulsar.loudnessGainDb"

/** 把响度增益写进播放条目的元数据 extras；没有数据时不写这个键。 */
fun Bundle.putLoudnessGain(gainDb: Float?) {
    gainDb?.let { putFloat(LOUDNESS_GAIN_DB_KEY, it) }
}

/**
 * 从播放条目的元数据 extras 读响度增益；没有数据返回 null。
 *
 * 显式判存在而不是直接 `getFloat`：后者缺键时返回 0f，虽然 0f 恰好也等价于"原样播放"，
 * 但"没有响度数据"和"增益恰好为 0"不该共用一条路径。
 */
fun Bundle.loudnessGainDb(): Float? =
    if (containsKey(LOUDNESS_GAIN_DB_KEY)) getFloat(LOUDNESS_GAIN_DB_KEY) else null

/** 最大衰减：再响的歌也不会被压到比这更低，异常数据不至于把歌变成蚊子叫。 */
private const val MIN_GAIN_DB = -24f

/**
 * Jellyfin 的响度增益（dB）→ 线性系数，即回放增益（ReplayGain）。
 *
 * Jellyfin 按 ReplayGain 2.0 的 -18 LUFS 基准给出 `NormalizationGain`：比基准响的歌是
 * 负值，安静的歌是正值。整首歌用同一个系数，不改音乐本身的动态。
 *
 * **只衰减不提升**：正增益（安静的歌）一律按 0 处理。本库最安静的曲目在 -18 LUFS 基准
 * 下要 +12.5 dB，硬提会顶穿 0 dBFS 产生削波失真；用整齐度换零失真，符合本项目
 * "尽量别动信号"的路线。
 *
 * 无数据 / 非有限值 → [UNITY_GAIN]（原样播放）。
 */
fun loudnessGainLinear(gainDb: Float?): Float {
    if (gainDb == null || !gainDb.isFinite()) return UNITY_GAIN
    if (gainDb >= 0f) return UNITY_GAIN
    return 10.0.pow(gainDb.coerceAtLeast(MIN_GAIN_DB) / 20.0).toFloat()
}

/**
 * 就地按 [gain] 缩放 [buffer] 剩余范围内的 PCM 样本，返回是否真的改动了数据。
 *
 * **只用于我们自己拥有的可写缓冲**（[LoudnessGainAudioProcessor] 的输出缓冲）。Media3
 * 交给 sink 的解码器输出缓冲是只读的（`MediaCodec.getOutputBuffer` 的契约），
 * `duplicate()` 也不会让它变可写——往那种缓冲写会抛 ReadOnlyBufferException。
 *
 * - [gain] == 1、编码不认识、缓冲区为空 → 返回 false，一个字节都不动（快路径）。
 * - 用 [ByteBuffer.duplicate] 操作：共享底层字节，但**不推进原 buffer 的 position/limit**
 *   ——Media3 的 sink 链依赖 position 记账，动它会打乱下游的 buffer 交接。
 * - 显式按小端读写：`duplicate()` 不继承字节序，且 Media3 音频管线传给 sink 的始终是
 *   小端 PCM（与 `PcmFloatConverter` 的假设一致）。
 * - 整数样本按位深饱和而不是回绕：正常路径增益 ≤ 1 触发不了，它是兜底，防止将来有人
 *   喂 >1 的增益时把波形回绕成反相噪声。
 * - 不处理 [C.ENCODING_PCM_8BIT]：本应用链路到不了（解码器输出 float/16/24/32），
 *   真遇到则原样放过，不静默改成错误的缩放。
 */
fun applyGainInPlace(buffer: ByteBuffer, encoding: Int, gain: Float): Boolean {
    val bytesPerSample = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> return false
    }
    if (gain == UNITY_GAIN || !gain.isFinite() || gain < 0f) return false

    val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val sampleCount = view.remaining() / bytesPerSample
    if (sampleCount <= 0) return false

    // 读用相对游标、写用绝对下标：同一个样本先读后写，若读写共用一个游标，
    // 每轮会前进 2 个样本的距离，一半就撞到 limit。
    val factor = gain.toDouble()
    var position = view.position()
    repeat(sampleCount) {
        view.position(position)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val scaled = (view.short * factor).roundToInt()
                    .coerceIn(MIN_PCM16, MAX_PCM16).toShort()
                view.putShort(position, scaled)
            }
            C.ENCODING_PCM_24BIT -> {
                val scaled = (readPcm24(view) * factor).roundToInt()
                    .coerceIn(MIN_PCM24, MAX_PCM24)
                view.put(position, (scaled and 0xFF).toByte())
                view.put(position + 1, (scaled shr 8 and 0xFF).toByte())
                view.put(position + 2, (scaled shr 16 and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> {
                val scaled = (view.int * factor).roundToLong()
                    .coerceIn(MIN_PCM32, MAX_PCM32).toInt()
                view.putInt(position, scaled)
            }
            else -> view.putFloat(position, view.float * gain)
        }
        position += bytesPerSample
    }
    return true
}

/**
 * 跨线程单值邮箱：PlaybackService 在换歌/改开关时写入当前曲目的线性增益，音频线程
 * （sink 的 handleBuffer）每帧读。与 [SilenceLevelMonitor] 同一套路——不在播放线程上
 * 做锁或分配。
 */
class LoudnessGainProvider {
    @Volatile
    var linearGain: Float = UNITY_GAIN
}

private const val MIN_PCM16 = -32768
private const val MAX_PCM16 = 32767
private const val MIN_PCM24 = -8388608
private const val MAX_PCM24 = 8388607
private const val MIN_PCM32 = -2147483648L
private const val MAX_PCM32 = 2147483647L
