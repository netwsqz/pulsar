package com.example.pulsar.playback

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessGainTest {

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

    private fun readShorts(buffer: ByteBuffer): List<Short> {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return List(view.remaining() / 2) { view.short }
    }

    private fun readPcm24(buffer: ByteBuffer): List<Int> {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return List(view.remaining() / 3) {
            val raw = (view.get().toInt() and 0xFF) or
                ((view.get().toInt() and 0xFF) shl 8) or
                ((view.get().toInt() and 0xFF) shl 16)
            // 24bit 是补码：最高位为 1 表示负数
            if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
        }
    }

    private fun readInts(buffer: ByteBuffer): List<Int> {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return List(view.remaining() / 4) { view.int }
    }

    private fun readFloats(buffer: ByteBuffer): List<Float> {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return List(view.remaining() / 4) { view.float }
    }

    // ---------- loudnessGainLinear：dB → 线性系数 ----------

    @Test
    fun `missing gain data plays back untouched`() {
        assertEquals(1f, loudnessGainLinear(null), 0.0001f)
    }

    @Test
    fun `non finite gain plays back untouched`() {
        assertEquals(1f, loudnessGainLinear(Float.NaN), 0.0001f)
        assertEquals(1f, loudnessGainLinear(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1f, loudnessGainLinear(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test
    fun `zero dB is unity`() {
        assertEquals(1f, loudnessGainLinear(0f), 0.0001f)
    }

    @Test
    fun `quiet tracks are never boosted`() {
        // 基准 -18 LUFS 下安静曲目会拿到正增益（最高约 +12.5 dB）。
        // 只衰减策略一律按原样播放：宁可不整齐，也不能削顶破音。
        assertEquals(1f, loudnessGainLinear(12.5f), 0.0001f)
    }

    @Test
    fun `minus six dB halves the amplitude`() {
        assertEquals(0.5012f, loudnessGainLinear(-6f), 0.001f)
    }

    @Test
    fun `minus twenty dB is one tenth`() {
        assertEquals(0.1f, loudnessGainLinear(-20f), 0.001f)
    }

    @Test
    fun `extreme attenuation is clamped so a track never becomes inaudible`() {
        // 钳到 -24 dB（约 0.063）；LUFS 数据异常时也不能把歌压成蚊子叫。
        assertEquals(0.0631f, loudnessGainLinear(-100f), 0.001f)
    }

    // ---------- applyGainInPlace：就地缩放 PCM ----------

    @Test
    fun `unity gain reports no change and leaves samples alone`() {
        val buffer = shortBuffer(1000, -2000)

        val changed = applyGainInPlace(buffer, C.ENCODING_PCM_16BIT, 1f)

        assertFalse(changed)
        assertEquals(listOf<Short>(1000, -2000), readShorts(buffer))
    }

    @Test
    fun `unrecognized encoding reports no change`() {
        val buffer = shortBuffer(1000)

        assertFalse(applyGainInPlace(buffer, -999, 0.5f))
        assertEquals(listOf<Short>(1000), readShorts(buffer))
    }

    @Test
    fun `empty buffer reports no change`() {
        assertFalse(applyGainInPlace(ByteBuffer.allocate(0), C.ENCODING_PCM_16BIT, 0.5f))
    }

    @Test
    fun `scales 16bit samples in place`() {
        val buffer = shortBuffer(16384, -16384, 0)

        assertTrue(applyGainInPlace(buffer, C.ENCODING_PCM_16BIT, 0.5f))

        assertEquals(listOf<Short>(8192, -8192, 0), readShorts(buffer))
    }

    @Test
    fun `scales 24bit samples in place`() {
        val buffer = pcm24Buffer(4194304, -4194304)

        assertTrue(applyGainInPlace(buffer, C.ENCODING_PCM_24BIT, 0.5f))

        assertEquals(listOf(2097152, -2097152), readPcm24(buffer))
    }

    @Test
    fun `scales 32bit samples in place`() {
        val buffer = pcm32Buffer(1073741824, -1073741824)

        assertTrue(applyGainInPlace(buffer, C.ENCODING_PCM_32BIT, 0.5f))

        assertEquals(listOf(536870912, -536870912), readInts(buffer))
    }

    @Test
    fun `scales float samples in place`() {
        val buffer = floatBuffer(0.8f, -0.4f)

        assertTrue(applyGainInPlace(buffer, C.ENCODING_PCM_FLOAT, 0.5f))

        assertEquals(listOf(0.4f, -0.2f), readFloats(buffer))
    }

    @Test
    fun `integer samples saturate instead of wrapping around`() {
        // 本包只喂 <=1 的增益，但保底：万一有人传了 >1，整数必须饱和而不是回绕成反相噪声。
        val buffer = shortBuffer(20000, -20000)

        assertTrue(applyGainInPlace(buffer, C.ENCODING_PCM_16BIT, 4f))

        assertEquals(listOf<Short>(Short.MAX_VALUE, Short.MIN_VALUE), readShorts(buffer))
    }

    @Test
    fun `does not move the buffer position or limit`() {
        val buffer = shortBuffer(100, -200, 300)
        val positionBefore = buffer.position()
        val limitBefore = buffer.limit()

        applyGainInPlace(buffer, C.ENCODING_PCM_16BIT, 0.5f)

        assertEquals(positionBefore, buffer.position())
        assertEquals(limitBefore, buffer.limit())
    }

    @Test
    fun `only touches the remaining samples of a partially consumed buffer`() {
        val buffer = shortBuffer(1000, 2000, 3000)
        buffer.position(2) // 第一个样本已被消费：只缩放剩下的

        applyGainInPlace(buffer, C.ENCODING_PCM_16BIT, 0.5f)

        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(1000, 1000, 1500), List(3) { view.getShort(it * 2) })
    }
}
