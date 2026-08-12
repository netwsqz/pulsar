package com.example.pulsar.playback.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** 将 Media3 的 float32 PCM 转为 USB 端点要求的有符号整数 PCM。 */
internal object PcmFloatConverter {
    private const val FLOAT_BYTES = 4

    fun convert(input: ByteBuffer, output: ByteArray, bitsPerSample: Int): Int {
        require(bitsPerSample == 16 || bitsPerSample == 24 || bitsPerSample == 32)
        val bytesPerSample = bitsPerSample / 8
        val sampleCount = minOf(input.remaining() / FLOAT_BYTES, output.size / bytesPerSample)
        val originalOrder = input.order()
        val floats = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = input.position()
        var outputPosition = 0
        repeat(sampleCount) {
            val sample = floats.getFloat()
            val value = toPcm(sample, bitsPerSample)
            writeLittleEndian(value, output, outputPosition, bytesPerSample)
            outputPosition += bytesPerSample
        }
        input.position(start + sampleCount * FLOAT_BYTES)
        input.order(originalOrder)
        return outputPosition
    }

    private fun toPcm(sample: Float, bits: Int): Long {
        val normalized = when {
            sample.isNaN() -> 0.0
            sample <= -1f -> -1.0
            sample >= 1f -> 1.0
            else -> sample.toDouble()
        }
        val negativeScale = -(1L shl (bits - 1)).toDouble()
        val positiveScale = (1L shl (bits - 1)) - 1.0
        val scaled = if (normalized < 0.0) normalized * -negativeScale else normalized * positiveScale
        return scaled.roundToLong().coerceIn(
            -(1L shl (bits - 1)),
            (1L shl (bits - 1)) - 1,
        )
    }

    private fun writeLittleEndian(value: Long, output: ByteArray, offset: Int, byteCount: Int) {
        repeat(byteCount) { index ->
            output[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
