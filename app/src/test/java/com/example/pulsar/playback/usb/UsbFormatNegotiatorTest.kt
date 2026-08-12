package com.example.pulsar.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbFormatNegotiator] 的单元测试。
 *
 * fixture 直接构造 [UsbAudioFormat]（不经描述符解析），覆盖：
 * - UAC1 固定采样率列表（44.1k / 48k）
 * - UAC2 空采样率列表（采样率运行时才可查，按动态可协商处理）
 * - UAC1 连续范围设备（解析器对 freqType=0 也产出空列表）
 */
class UsbFormatNegotiatorTest {

    /** UAC1 格式：默认 2ch/16bit，采样率 44.1k+48k，maxPacketSize=256。 */
    private fun uac1(
        bitsPerSample: Int = 16,
        channels: Int = 2,
        sampleRates: List<Int> = listOf(44100, 48000),
        maxPacketSize: Int = 256,
    ): UsbAudioFormat = UsbAudioFormat(
        uacVersion = UsbAudioFormat.UAC_VERSION_1,
        interfaceNumber = 1,
        altSetting = 1,
        endpointAddress = 0x01,
        maxPacketSize = maxPacketSize,
        bInterval = 1,
        bSynchAddress = 0,
        channels = channels,
        bitsPerSample = bitsPerSample,
        sampleRates = sampleRates,
    )

    /** UAC2 格式：默认 2ch/24bit，采样率空列表，maxPacketSize=1024。 */
    private fun uac2(
        bitsPerSample: Int = 24,
        channels: Int = 2,
        maxPacketSize: Int = 1024,
    ): UsbAudioFormat = UsbAudioFormat(
        uacVersion = UsbAudioFormat.UAC_VERSION_2,
        interfaceNumber = 1,
        altSetting = 1,
        endpointAddress = 0x01,
        maxPacketSize = maxPacketSize,
        bInterval = 1,
        bSynchAddress = 0,
        channels = channels,
        bitsPerSample = bitsPerSample,
        sampleRates = emptyList(),
    )

    // ---- negotiate --------------------------------------------------------

    @Test
    fun `UAC1 精确命中 44100Hz 16bit 2ch`() {
        val format = uac1()

        val result = UsbFormatNegotiator.negotiate(listOf(format), 44100, 16, 2)

        assertSame(format, result)
    }

    @Test
    fun `UAC1 只有 48k 但请求 44100 时仍可靠位深声道命中`() {
        // 采样率拿不到 +2，但位深+声道各 +1，总分 2 依然命中（验证打分逻辑）。
        val format = uac1(sampleRates = listOf(48000))

        val result = UsbFormatNegotiator.negotiate(listOf(format), 44100, 16, 2)

        assertSame(format, result)
    }

    @Test
    fun `UAC2 空列表按动态可协商命中任意合法采样率`() {
        val format = uac2()

        assertSame(format, UsbFormatNegotiator.negotiate(listOf(format), 44100, 24, 2))
        assertSame(format, UsbFormatNegotiator.negotiate(listOf(format), 88200, 24, 2))
        assertSame(format, UsbFormatNegotiator.negotiate(listOf(format), 192000, 24, 2))
    }

    @Test
    fun `位深不匹配但有声道和采样率匹配时仍命中`() {
        // 位深 24 不匹配（+0），但声道 +1、采样率 +2，总分 >0 仍命中。
        val format = uac1(bitsPerSample = 24, sampleRates = listOf(44100))

        val result = UsbFormatNegotiator.negotiate(listOf(format), 44100, 16, 2)

        assertSame(format, result)
    }

    @Test
    fun `声道位深采样率全部不匹配时返回 null`() {
        val format = uac1(bitsPerSample = 24, channels = 6, sampleRates = listOf(48000))

        assertNull(UsbFormatNegotiator.negotiate(listOf(format), 44100, 16, 2))
    }

    @Test
    fun `空格式列表返回 null`() {
        assertNull(UsbFormatNegotiator.negotiate(emptyList(), 44100, 16, 2))
    }

    @Test
    fun `多个格式中选分数最高者`() {
        // onlyRate 只命中采样率（score 2，maxPacketSize 更大）；perfect 全命中（score 4）。
        val onlyRate = uac1(bitsPerSample = 24, channels = 6, sampleRates = listOf(44100), maxPacketSize = 512)
        val perfect = uac1()

        val result = UsbFormatNegotiator.negotiate(listOf(onlyRate, perfect), 44100, 16, 2)

        assertSame(perfect, result)
    }

    @Test
    fun `同分时选 maxPacketSize 更大的格式`() {
        // a：只命中采样率（score 2）；b：只命中位深+声道（score 2）。平手靠 maxPacketSize 决胜。
        val a = uac1(bitsPerSample = 24, channels = 6, sampleRates = listOf(44100), maxPacketSize = 128)
        val b = uac1(sampleRates = listOf(48000), maxPacketSize = 256)

        val result = UsbFormatNegotiator.negotiate(listOf(a, b), 44100, 16, 2)

        assertSame(b, result)
    }

    @Test
    fun `UAC1 连续范围设备空列表同样按动态协商`() {
        // 解析器对 UAC1 freqType=0（连续范围）的设备同样产出空列表，协商按动态可协商处理。
        val format = uac1(sampleRates = emptyList())

        assertSame(format, UsbFormatNegotiator.negotiate(listOf(format), 88200, 16, 2))
        assertTrue(UsbFormatNegotiator.supportsRate(format, 88200))
    }

    // ---- supportsRate -----------------------------------------------------

    @Test
    fun `supportsRate 对 UAC1 固定列表做包含判断`() {
        val format = uac1(sampleRates = listOf(44100, 48000))

        assertTrue(UsbFormatNegotiator.supportsRate(format, 44100))
        assertTrue(UsbFormatNegotiator.supportsRate(format, 48000))
        assertTrue(!UsbFormatNegotiator.supportsRate(format, 88200))
        assertTrue(!UsbFormatNegotiator.supportsRate(format, 0))
    }

    @Test
    fun `supportsRate 对 UAC2 空列表按合法区间判断`() {
        val format = uac2()

        assertTrue(UsbFormatNegotiator.supportsRate(format, 44100))
        assertTrue(UsbFormatNegotiator.supportsRate(format, 768000))
        assertTrue(!UsbFormatNegotiator.supportsRate(format, 7999))
        assertTrue(!UsbFormatNegotiator.supportsRate(format, 768001))
        assertTrue(!UsbFormatNegotiator.supportsRate(format, 0))
    }

    // ---- nearestRate ------------------------------------------------------

    @Test
    fun `nearestRate 固定列表取最接近且平手取小`() {
        val format = uac1(sampleRates = listOf(44100, 48000))

        assertEquals(44100, UsbFormatNegotiator.nearestRate(format, 46050)) // 正中间，平手取小
        assertEquals(44100, UsbFormatNegotiator.nearestRate(format, 8000))  // 下方越界，取最小端
        assertEquals(48000, UsbFormatNegotiator.nearestRate(format, 96000)) // 上方越界，取最大端
        assertEquals(44100, UsbFormatNegotiator.nearestRate(format, 44100)) // 精确命中
    }

    @Test
    fun `nearestRate UAC2 空列表直接返回请求采样率`() {
        val format = uac2()

        assertEquals(44100, UsbFormatNegotiator.nearestRate(format, 44100))
        assertEquals(192000, UsbFormatNegotiator.nearestRate(format, 192000))
    }

    @Test
    fun `nearestRate 空列表非法采样率优雅返回 null 不抛异常`() {
        val format = uac2()

        assertNull(UsbFormatNegotiator.nearestRate(format, 0))
        assertNull(UsbFormatNegotiator.nearestRate(format, -44100))
        assertNull(UsbFormatNegotiator.nearestRate(format, 999999))
    }
}
