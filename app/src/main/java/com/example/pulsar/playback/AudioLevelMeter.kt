package com.example.pulsar.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 无法计算电平（空 buffer / 全零 / 不支持的编码）时返回的静音下限（dBFS）。 */
const val SILENT_FLOOR_DB = -120.0

/**
 * 计算 [buffer] 当前剩余数据的电平（dBFS，0 dB = 满幅），用于静音跳曲检测。
 *
 * 只读取数据，不改变 [buffer] 的 position/limit（内部用 [ByteBuffer.duplicate] 操作），
 * 调用方（音频 sink 的 handleBuffer）可以放心在计算电平后继续正常消费原 buffer。
 *
 * 支持 [C.ENCODING_PCM_16BIT] / [C.ENCODING_PCM_24BIT] / [C.ENCODING_PCM_32BIT] /
 * [C.ENCODING_PCM_FLOAT]，按 RMS 归一化到 [-1, 1] 后取 20*log10(rms)。不支持的编码
 * 或空数据统一返回 [SILENT_FLOOR_DB]，避免 log10(0) 产生 -Infinity。
 */
fun computeLevelDb(buffer: ByteBuffer, encoding: Int): Double {
    val bytesPerSample = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> return SILENT_FLOOR_DB
    }

    // duplicate() 不继承原 buffer 的字节序（默认重置为 BIG_ENDIAN），必须显式同步，
    // 否则多字节样本会被错误解读（这里的数据始终是小端 PCM）。
    val duplicate = buffer.duplicate().order(buffer.order())
    val sampleCount = duplicate.remaining() / bytesPerSample
    if (sampleCount <= 0) return SILENT_FLOOR_DB

    var sumSquares = 0.0
    repeat(sampleCount) {
        val normalized = when (encoding) {
            C.ENCODING_PCM_16BIT -> duplicate.short.toDouble() / SCALE_16BIT
            C.ENCODING_PCM_24BIT -> readPcm24(duplicate).toDouble() / SCALE_24BIT
            C.ENCODING_PCM_32BIT -> duplicate.int.toDouble() / SCALE_32BIT
            else -> duplicate.float.toDouble()
        }
        sumSquares += normalized * normalized
    }

    val rms = sqrt(sumSquares / sampleCount)
    if (rms <= 0.0) return SILENT_FLOOR_DB
    return max(20.0 * log10(rms), SILENT_FLOOR_DB)
}

/** 从小端 buffer 读 3 字节有符号 PCM24 样本并符号扩展到 Int。 */
internal fun readPcm24(buffer: ByteBuffer): Int {
    val b0 = buffer.get().toInt() and 0xFF
    val b1 = buffer.get().toInt() and 0xFF
    val b2 = buffer.get().toInt() and 0xFF
    val value = b0 or (b1 shl 8) or (b2 shl 16)
    return if (value and 0x800000 != 0) value or -0x1000000 else value
}

private const val SCALE_16BIT = 32768.0
private const val SCALE_24BIT = 8388608.0
private const val SCALE_32BIT = 2147483648.0
