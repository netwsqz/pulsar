package com.example.pulsar.playback.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmFloatConverterTest {
    @Test
    fun `float samples convert to packed 24 bit little endian`() {
        val input = floats(-1f, 0f, 1f)
        val output = ByteArray(9)

        val written = PcmFloatConverter.convert(input, output, 24)

        assertEquals(9, written)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x80.toByte(),
                0x00, 0x00, 0x00,
                0xFF.toByte(), 0xFF.toByte(), 0x7F,
            ),
            output,
        )
    }

    @Test
    fun `float samples convert to 16 and 32 bit`() {
        val input = floats(-0.5f, 0.5f)
        val output16 = ByteArray(4)
        val output32 = ByteArray(8)

        assertEquals(4, PcmFloatConverter.convert(input.duplicate(), output16, 16))
        assertEquals(8, PcmFloatConverter.convert(input.duplicate(), output32, 32))
        assertArrayEquals(byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x40), output16)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0xC0.toByte(),
                0x00, 0x00, 0x00, 0x40,
            ),
            output32,
        )
    }

    @Test
    fun `values are clipped and special values are safe`() {
        val input = floats(-2f, 2f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        val output = ByteArray(15)

        PcmFloatConverter.convert(input, output, 24)

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x80.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0x7F,
                0x00, 0x00, 0x00,
                0x00, 0x00, 0x80.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0x7F,
            ),
            output,
        )
    }

    @Test
    fun `input position advances only for complete output`() {
        val input = floats(0.25f, -0.25f)
        val output = ByteArray(3)

        val written = PcmFloatConverter.convert(input, output, 24)

        assertEquals(3, written)
        assertEquals(4, input.position())
    }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach(::putFloat)
            flip()
        }
}
