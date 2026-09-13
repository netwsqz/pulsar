package com.example.pulsar.playback.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IsoPumpWatchdog] 的单元测试。
 *
 * 关键背景：native 驱动（usb_audio_driver.cpp）的 `played_frames_` 计数器只增不减
 * （flush 只重置 ring 读指针、从不清零计数），所以看门狗用「推进检测」。
 *
 * 但「播放帧不推进」有两个完全不同的原因，必须分开：
 * - **泵卡死**：事件线程还在跑（心跳在涨），但等时传输不再完成（xHCI/驱动组合楔住）。
 * - **CPU 饥饿**：事件线程压根没被调度（心跳也不涨），播放帧自然不涨。
 *
 * 后者是前台重负载下的常态，会自愈（线程恢复后一口气补完积压的 URB）；当它判死会
 * 触发 flushRing + 重启流，丢弃 ring 里约 1 秒未播音频 = 听感「断音/跳秒」。
 * 所以只在心跳也停了很久（超过 [IsoPumpWatchdog.DEFAULT_STARVATION_CAP_NS] 硬上限，
 * 防真死锁）时才判死。
 */
class IsoPumpWatchdogTest {

    private val warmup = 400_000_000L
    private val stall = 400_000_000L
    private val cap = 2_000_000_000L

    @Test
    fun `未开始写入不会判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(1_000L)
        assertFalse(w.check(2_000_000_000L, 0L, heartbeat = 0L, loopExited = false))
        assertFalse(w.tripped)
    }

    @Test
    fun `播放帧持续推进则不会判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        var t = 1L
        var frames = 0L
        var heartbeat = 0L
        repeat(100) { i ->
            frames += 500
            heartbeat++
            t = 1L + i * 50_000_000L // 每 50ms 推进一次，远小于 stall
            assertFalse(w.check(t, frames, heartbeat, false))
        }
        assertFalse(w.tripped)
    }

    @Test
    fun `暖机期内无推进不判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        assertFalse(w.check(warmup - 1L, 0L, heartbeat = 0L, loopExited = false))
        assertFalse(w.tripped)
    }

    @Test
    fun `暖机后持续无推进判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        assertFalse(w.check(warmup - 1L, 0L, heartbeat = 0L, loopExited = false))
        // 心跳在涨 = 事件线程有被调度，但泵不推进 → 真卡死
        assertTrue(w.check(warmup + stall, 0L, heartbeat = 1L, loopExited = false))
        assertTrue(w.tripped)
    }

    @Test
    fun `卡死后即使推进也不会复活直到重置`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        assertTrue(w.check(warmup + stall, 0L, heartbeat = 1L, loopExited = false))
        assertTrue(w.check(warmup + stall + 1L, 5_000L, heartbeat = 2L, loopExited = false))
        w.reset(warmup + stall + 1L)
        assertFalse(w.tripped)
    }

    @Test
    fun `暖机期内有推进会顺延卡死窗口`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        // 暖机 300ms 时推进一次 → 卡死窗口顺延到本次推进之后
        assertFalse(w.check(300_000_000L, 1_000L, heartbeat = 0L, loopExited = false))
        // 距上次推进 300ms（<stall），但距首次写入 600ms（已过暖机）→ 仍不判死
        assertFalse(w.check(600_000_000L, 1_000L, heartbeat = 1L, loopExited = false))
        // 距上次推进 500ms（>stall），且心跳刚涨过 → 判死
        assertTrue(w.check(800_000_000L, 1_000L, heartbeat = 2L, loopExited = false))
        assertTrue(w.tripped)
    }

    // ---- 新增：心跳（区分「泵卡死」与「CPU 饥饿」）----------------------------------

    @Test
    fun `心跳在推进但播放帧停滞时判定泵卡死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        // 心跳一直在涨 = 事件线程有被调度；播放帧始终 0 = 泵不推进
        assertFalse(w.check(300_000_000L, 0L, heartbeat = 3L, loopExited = false))
        assertTrue(w.check(500_000_000L, 0L, heartbeat = 5L, loopExited = false))
        assertTrue(w.tripped)
    }

    @Test
    fun `心跳与播放帧都停滞时判定为饥饿不判死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        // 稳态：两者都在推进
        assertFalse(w.check(50_000_000L, 1_000L, heartbeat = 1L, loopExited = false))
        assertFalse(w.check(100_000_000L, 2_000L, heartbeat = 2L, loopExited = false))
        // 从 100ms 起两者一起冻结（事件线程被前台重负载饿住）
        assertFalse(w.check(600_000_000L, 2_000L, heartbeat = 2L, loopExited = false))
        assertFalse(w.check(1_600_000_000L, 2_000L, heartbeat = 2L, loopExited = false))
        assertFalse(w.tripped)
    }

    @Test
    fun `饥饿超过硬上限才判定卡死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        assertFalse(w.check(100_000_000L, 2_000L, heartbeat = 2L, loopExited = false))
        // 心跳冻结到超过 cap（2s）→ 不再无限等，判死
        assertTrue(w.check(100_000_000L + cap, 2_000L, heartbeat = 2L, loopExited = false))
        assertTrue(w.tripped)
    }

    @Test
    fun `短暂饥饿结束后恢复推进则不判死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        assertFalse(w.check(100_000_000L, 1_000L, heartbeat = 1L, loopExited = false))
        // 饥饿 1.2s，心跳与播放帧都冻结（远小于 cap）
        assertFalse(w.check(1_300_000_000L, 1_000L, heartbeat = 1L, loopExited = false))
        // 恢复：线程与泵一起猛推进（一口气补完积压的 URB）
        assertFalse(w.check(1_350_000_000L, 90_000L, heartbeat = 2L, loopExited = false))
        assertFalse(w.tripped)
    }

    @Test
    fun `事件循环已退出时立即判死`() {
        val w = IsoPumpWatchdog(warmup, stall, cap)
        w.reset(0L)
        w.onWrite(1L)
        // 循环退出 = 泵确实没了（native 报了明确状态），不必等暖机/窗口
        assertTrue(w.check(1_000_000L, 0L, heartbeat = 0L, loopExited = true))
        assertTrue(w.tripped)
    }
}
