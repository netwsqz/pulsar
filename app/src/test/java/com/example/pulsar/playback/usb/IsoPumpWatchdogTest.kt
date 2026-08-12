package com.example.pulsar.playback.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IsoPumpWatchdog] 的单元测试。
 *
 * 关键背景：native 驱动（usb_audio_driver.cpp）的 `played_frames_` 计数器只增不减
 * （flush 只重置 ring 读指针、从不清零计数），所以看门狗用「推进检测」：
 * 暖机期内不判死，暖机期后若 [stall] 窗口内 playedFrames 无推进即判定卡死。
 */
class IsoPumpWatchdogTest {

    private val warmup = 400_000_000L
    private val stall = 400_000_000L

    @Test
    fun `未开始写入不会判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall)
        w.reset(1_000L)
        assertFalse(w.check(2_000_000_000L, 0L))
        assertFalse(w.tripped)
    }

    @Test
    fun `播放帧持续推进则不会判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall)
        w.reset(0L)
        w.onWrite(1L)
        var t = 1L
        var frames = 0L
        repeat(100) { i ->
            frames += 500
            t = 1L + i * 50_000_000L // 每 50ms 推进一次，远小于 stall
            assertFalse(w.check(t, frames))
        }
        assertFalse(w.tripped)
    }

    @Test
    fun `暖机期内无推进不判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall)
        w.reset(0L)
        w.onWrite(1L)
        assertFalse(w.check(warmup - 1L, 0L))
        assertFalse(w.tripped)
    }

    @Test
    fun `暖机后持续无推进判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall)
        w.reset(0L)
        w.onWrite(1L)
        assertFalse(w.check(warmup - 1L, 0L))
        assertTrue(w.check(warmup + stall, 0L))
        assertTrue(w.tripped)
    }

    @Test
    fun `卡死后即使推进也不会复活直到重置`() {
        val w = IsoPumpWatchdog(warmup, stall)
        w.reset(0L)
        w.onWrite(1L)
        assertTrue(w.check(warmup + stall, 0L))
        assertTrue(w.check(warmup + stall + 1L, 5_000L))
        w.reset(warmup + stall + 1L)
        assertFalse(w.tripped)
    }

    @Test
    fun `暖机期内有推进会顺延卡死窗口`() {
        val w = IsoPumpWatchdog(warmup, stall)
        w.reset(0L)
        w.onWrite(1L)
        // 暖机 300ms 时推进一次 → 卡死窗口顺延到本次推进之后
        assertFalse(w.check(300_000_000L, 1_000L))
        // 距上次推进 300ms（<stall），但距首次写入 600ms（已过暖机）→ 仍不判死
        assertFalse(w.check(600_000_000L, 1_000L))
        // 距上次推进 500ms（>stall）→ 判死
        assertTrue(w.check(800_000_000L, 1_000L))
        assertTrue(w.tripped)
    }
}
