package com.example.pulsar.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UsbIsoScheduler] 与 [IsoFrameAccumulator] 的单元测试。
 *
 * 关键数学背景（USB 等时传输时序）：
 * - 高速（HS）每 125µs 一个微帧 → 每秒 8000 微帧
 * - 全速（FS）每 1ms 一帧 → 每秒 1000 帧
 * - 采样率不整除微帧率（如 44.1kHz / 8000 = 5.5125 帧/微帧）时，
 *   用 16.16 定点分数累加器让每包帧数在 5/6 之间交替，长期零漂移。
 */
class UsbIsoSchedulerTest {

    // ---- microframesPerSec -----------------------------------------------

    @Test
    fun `高速每秒 8000 微帧`() {
        assertEquals(8000, UsbIsoScheduler.microframesPerSec(isHighSpeed = true))
    }

    @Test
    fun `全速每秒 1000 微帧`() {
        assertEquals(1000, UsbIsoScheduler.microframesPerSec(isHighSpeed = false))
    }

    // ---- framesPerMicroframeQ16 ------------------------------------------

    @Test
    fun `44100 高速下 16点16 定点值为 361267`() {
        // 44100 * 65536 / 8000 = 361267.2 → 截断 361267
        assertEquals(361_267L, UsbIsoScheduler.framesPerMicroframeQ16(44_100, 8_000))
    }

    @Test
    fun `48000 高速下是精确整数 393216`() {
        // 48000 * 65536 / 8000 = 393216 = 6 * 65536 → 恒 6 帧/微帧
        assertEquals(393_216L, UsbIsoScheduler.framesPerMicroframeQ16(48_000, 8_000))
    }

    @Test
    fun `192000 高速下 Long 运算不溢出且精确`() {
        // 192000 * 65536 = 12,582,912,000 超 Int 范围，必须用 Long。
        assertEquals(1_572_864L, UsbIsoScheduler.framesPerMicroframeQ16(192_000, 8_000))
    }

    @Test
    fun `44100 全速下每毫秒约 44 帧`() {
        // 44100 * 65536 / 1000 = 2890137.6 → 截断 2890137
        assertEquals(2_890_137L, UsbIsoScheduler.framesPerMicroframeQ16(44_100, 1_000))
    }

    // ---- IsoFrameAccumulator ---------------------------------------------

    @Test
    fun `48k 恒 6 帧每微帧 8000 微帧总和精确等于 48000`() {
        val acc = IsoFrameAccumulator(393_216L)
        var total = 0L
        repeat(8_000) { total += acc.nextFrameCount() }
        assertEquals(48_000L, total)
    }

    @Test
    fun `44100 长时间运行无漂移`() {
        val acc = IsoFrameAccumulator(361_267L)
        var total = 0L
        var window = 0L
        repeat(8_000) { i ->
            val frames = acc.nextFrameCount()
            total += frames
            window += frames
            if ((i + 1) % 8 == 0) {
                // 8 微帧平均 44.1 帧 → 窗口总和必为 44 或 45
                assertTrue("8 微帧窗口应约 44 帧, 实际 $window", window in 44L..45L)
                window = 0L
            }
        }
        // 8000 微帧 = 1 秒，应输出约 44100 帧（±2 是定点截断误差）
        assertTrue("8000 微帧应约 44100 帧, 实际 $total", total in 44_098L..44_102L)
    }

    @Test
    fun `44100 全速 1000 毫秒总和约 44100`() {
        val acc = IsoFrameAccumulator(2_890_137L)
        var total = 0L
        repeat(1_000) { total += acc.nextFrameCount() }
        assertTrue("1000 毫秒应约 44100 帧, 实际 $total", total in 44_098L..44_102L)
    }

    @Test
    fun `低采样率跳包 返回 0 的微帧`() {
        // q16=30000 < 65536：平均 0.458 帧/微帧 → 大多数微帧返回 0，偶尔 1。
        val acc = IsoFrameAccumulator(30_000L)
        val firstTen = (1..10).map { acc.nextFrameCount() }
        assertEquals(0L, firstTen.first())
        assertTrue("应有返回 0 的微帧", firstTen.any { it == 0L })
        assertTrue("应有返回 1 的微帧", firstTen.any { it == 1L })
        // floor(10 * 30000 / 65536) = floor(4.5776) = 4
        assertEquals(4L, firstTen.sum())
    }

    // ---- maxFramesPerPacket ----------------------------------------------

    @Test
    fun `每包最大帧数按包大小除以每帧字节`() {
        // 1024 字节包 / 6 字节每帧（24bit 立体声）= 170 帧
        assertEquals(170, UsbIsoScheduler.maxFramesPerPacket(1_024, 6))
        assertEquals(256, UsbIsoScheduler.maxFramesPerPacket(1_024, 4))
    }

    @Test
    fun `非正参数返回 0`() {
        assertEquals(0, UsbIsoScheduler.maxFramesPerPacket(0, 6))
        assertEquals(0, UsbIsoScheduler.maxFramesPerPacket(1_024, 0))
        assertEquals(0, UsbIsoScheduler.maxFramesPerPacket(-1, 6))
    }

    // ---- uframesPerPacketInterval ----------------------------------------

    @Test
    fun `高速 bInterval 换算为微帧数`() {
        // HS 间隔 = 2^(bInterval-1) 微帧
        assertEquals(1, UsbIsoScheduler.uframesPerPacketInterval(1, isHighSpeed = true))
        assertEquals(2, UsbIsoScheduler.uframesPerPacketInterval(2, isHighSpeed = true))
        assertEquals(8, UsbIsoScheduler.uframesPerPacketInterval(4, isHighSpeed = true))
    }

    @Test
    fun `全速 bInterval 直接为累积器刻度数`() {
        // 全速累积器刻度 = 1ms 帧（microframesPerSec=1000），每包刻度数 = bInterval。
        assertEquals(1, UsbIsoScheduler.uframesPerPacketInterval(1, isHighSpeed = false))
        assertEquals(2, UsbIsoScheduler.uframesPerPacketInterval(2, isHighSpeed = false))
    }

    @Test
    fun `非法 bInterval 返回 1`() {
        assertEquals(1, UsbIsoScheduler.uframesPerPacketInterval(0, isHighSpeed = true))
        assertEquals(1, UsbIsoScheduler.uframesPerPacketInterval(-1, isHighSpeed = false))
    }
}
