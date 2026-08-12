package com.example.pulsar.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UsbStreamParams] 的单元测试：Kotlin 侧推导 native 等时泵参数的契约。
 *
 * 这些数值直接决定 native 泵的每包帧数/字节数，因此是 TDD 重点。
 */
class UsbStreamParamsTest {

    /** UAC1 16bit/2ch：subframe=2 字节，maxPacketSize=256，bInterval=1。 */
    private fun uac1(
        bitsPerSample: Int = 16,
        channels: Int = 2,
        subframeSize: Int = 2,
        maxPacketSize: Int = 256,
        bInterval: Int = 1,
    ): UsbAudioFormat = UsbAudioFormat(
        uacVersion = UsbAudioFormat.UAC_VERSION_1,
        interfaceNumber = 1, altSetting = 1, endpointAddress = 0x01,
        maxPacketSize = maxPacketSize, bInterval = bInterval, bSynchAddress = 0,
        channels = channels, bitsPerSample = bitsPerSample,
        sampleRates = listOf(44100, 48000), subframeSize = subframeSize,
    )

    /** UAC2 24bit/2ch：subslot=3 字节，maxPacketSize=1024，bInterval=1。 */
    private fun uac2(
        bitsPerSample: Int = 24,
        channels: Int = 2,
        subframeSize: Int = 3,
        maxPacketSize: Int = 1024,
        bInterval: Int = 1,
        clockId: Int = 1,
    ): UsbAudioFormat = UsbAudioFormat(
        uacVersion = UsbAudioFormat.UAC_VERSION_2,
        interfaceNumber = 1, altSetting = 1, endpointAddress = 0x01,
        maxPacketSize = maxPacketSize, bInterval = bInterval, bSynchAddress = 0,
        channels = channels, bitsPerSample = bitsPerSample,
        sampleRates = emptyList(), subframeSize = subframeSize, clockId = clockId,
    )

    @Test
    fun `UAC1 16bit 立体声 44100 高速的参数`() {
        val p = UsbStreamParams.derive(uac1(), sampleRate = 44100, isHighSpeed = true)

        assertEquals(1, p.ticksPerPacket)          // bInterval=1 → 每 125µs 一包
        assertEquals(4, p.bytesPerFrame)           // 2 声道 × 2 字节
        assertEquals(361_267L, p.framesPerUframeQ16)  // 44100*65536/8000
        assertEquals(64, p.maxFramesPerPacket)     // 256 / 4
    }

    @Test
    fun `UAC2 24bit 立体声 192000 高速 Long 不溢出`() {
        val p = UsbStreamParams.derive(uac2(), sampleRate = 192000, isHighSpeed = true)

        assertEquals(6, p.bytesPerFrame)           // 2 声道 × 3 字节
        assertEquals(1_572_864L, p.framesPerUframeQ16)  // 192000*65536/8000
        assertEquals(170, p.maxFramesPerPacket)    // 1024 / 6
    }

    @Test
    fun `全速 bInterval 决定每包刻度`() {
        val p = UsbStreamParams.derive(uac1(bInterval = 2), sampleRate = 44100, isHighSpeed = false)

        assertEquals(2, p.ticksPerPacket)          // 全速：刻度数 = bInterval
        assertEquals(2_890_137L, p.framesPerUframeQ16)  // 44100*65536/1000
    }

    @Test
    fun `高速 bInterval=4 每 1ms 一包共 8 个微帧刻度`() {
        val p = UsbStreamParams.derive(uac1(bInterval = 4), sampleRate = 44100, isHighSpeed = true)

        assertEquals(8, p.ticksPerPacket)          // 2^(4-1)=8 微帧
        assertEquals(361_267L, p.framesPerUframeQ16)
    }

    @Test
    fun `真机回归 48000 高速每包 6 帧 误当全速会得 48 帧致掉线`() {
        // 真机小尾巴（UAC2, 48kHz/16bit/2ch, bInterval=1）跑在 USB 高速链路。
        // 曾因 isHighSpeed 硬编码 false，按全速算出 48 帧/包（1536 字节/传输），
        // 8 倍超速塞进 125µs 微帧导致等时端点掉线（LIBUSB_TRANSFER_NO_DEVICE）。
        // 高速正确值：48000/8000 = 6 帧/包。这里钉死两种速度的确切差异。
        val hs = UsbStreamParams.derive(uac1(), sampleRate = 48000, isHighSpeed = true)
        assertEquals(393_216L, hs.framesPerUframeQ16)   // 48000*65536/8000 → 6 帧/刻度

        val fs = UsbStreamParams.derive(uac1(), sampleRate = 48000, isHighSpeed = false)
        assertEquals(3_145_728L, fs.framesPerUframeQ16) // 48000*65536/1000 → 48 帧/刻度（错误路径）
    }

    @Test
    fun `subframeSize 未知时按位深向上取整兜底`() {
        val p = UsbStreamParams.derive(uac1(subframeSize = 0), sampleRate = 48000, isHighSpeed = true)

        assertEquals(4, p.bytesPerFrame)           // 16bit → 2 字节 × 2ch
    }

    @Test
    fun `24bit 无 subframeSize 时按 3 字节兜底`() {
        val p = UsbStreamParams.derive(uac2(subframeSize = 0), sampleRate = 48000, isHighSpeed = true)

        assertEquals(6, p.bytesPerFrame)           // 3 字节 × 2ch
    }
}
