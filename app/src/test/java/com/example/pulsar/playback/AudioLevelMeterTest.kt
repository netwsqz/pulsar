package com.example.pulsar.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLevelMeterTest {

    private fun shortBuffer(vararg samples: Short): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }

    private fun pcm24Buffer(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 3).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { value ->
            buffer.put((value and 0xFF).toByte())
            buffer.put(((value shr 8) and 0xFF).toByte())
            buffer.put(((value shr 16) and 0xFF).toByte())
        }
        buffer.flip()
        return buffer
    }

    private fun pcm32Buffer(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putInt(it) }
        buffer.flip()
        return buffer
    }

    private fun floatBuffer(vararg samples: Float): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        buffer.flip()
        return buffer
    }

    @Test
    fun `computeLevelDb returns floor for empty buffer`() {
        val buffer = ByteBuffer.allocate(0)

        assertEquals(SILENT_FLOOR_DB, computeLevelDb(buffer, C.ENCODING_PCM_16BIT), 0.001)
    }

    @Test
    fun `computeLevelDb returns floor for all zero 16bit samples`() {
        val buffer = shortBuffer(0, 0, 0, 0)

        assertEquals(SILENT_FLOOR_DB, computeLevelDb(buffer, C.ENCODING_PCM_16BIT), 0.001)
    }

    @Test
    fun `computeLevelDb calculates half scale amplitude for 16bit PCM`() {
        val buffer = shortBuffer(16384, -16384, 16384, -16384)

        assertEquals(-6.0206, computeLevelDb(buffer, C.ENCODING_PCM_16BIT), 0.01)
    }

    @Test
    fun `computeLevelDb calculates half scale amplitude for 24bit PCM`() {
        val buffer = pcm24Buffer(4194304, -4194304, 4194304, -4194304)

        assertEquals(-6.0206, computeLevelDb(buffer, C.ENCODING_PCM_24BIT), 0.01)
    }

    @Test
    fun `computeLevelDb calculates half scale amplitude for 32bit PCM`() {
        val buffer = pcm32Buffer(1073741824, -1073741824, 1073741824, -1073741824)

        assertEquals(-6.0206, computeLevelDb(buffer, C.ENCODING_PCM_32BIT), 0.01)
    }

    @Test
    fun `computeLevelDb calculates half scale amplitude for float PCM`() {
        val buffer = floatBuffer(0.5f, -0.5f, 0.5f, -0.5f)

        assertEquals(-6.0206, computeLevelDb(buffer, C.ENCODING_PCM_FLOAT), 0.01)
    }

    @Test
    fun `computeLevelDb returns approximately zero dB for full scale 16bit PCM`() {
        val buffer = shortBuffer(Short.MIN_VALUE, Short.MIN_VALUE)

        assertEquals(0.0, computeLevelDb(buffer, C.ENCODING_PCM_16BIT), 0.01)
    }

    @Test
    fun `computeLevelDb returns floor for unrecognized encoding`() {
        val buffer = shortBuffer(16384, -16384)

        assertEquals(SILENT_FLOOR_DB, computeLevelDb(buffer, -999), 0.001)
    }

    @Test
    fun `computeLevelDb does not advance the buffer position`() {
        val buffer = shortBuffer(100, -200, 300)
        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        computeLevelDb(buffer, C.ENCODING_PCM_16BIT)

        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }
}
