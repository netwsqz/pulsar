package com.example.pulsar.playback.usb

/**
 * 等时泵卡死看门狗。
 *
 * 判断依据：native 驱动（usb_audio_driver.cpp）的 `played_frames_` 计数器**只增不减**
 * ——flush 只重置 ring 读指针（pending_flush_ → ring_read_），从不清零计数。
 * 所以必须用「推进检测」（playedFrames 是否增长）而非「相等检测」。
 *
 * 语义（暖机 + 卡死，参考 Tryptify 的实现思路）：
 * - **暖机期**：从首次写入起 [warmupNs] 内，即使无推进也不判定。原因是刚
 *   `start_stream` 时等时 URBs 首次提交有延迟，设备还没开始消费，此刻的
 *   playedFrames 停滞是正常的。
 * - **卡死期**：暖机期过后，若 [stallNs] 内 playedFrames 无任何推进，判定
 *   泵卡死（典型场景：某些 xHCI 控制器/驱动组合的等时端点楔住）。调用方应
 *   回退到 delegate 输出，避免无声。
 *
 * 纯时间逻辑，不依赖 Android 时钟，可单元测试。`nowNs` 由调用方注入
 * （SystemClock.elapsedRealtimeNanos()）。
 */
class IsoPumpWatchdog(
    private val warmupNs: Long,
    private val stallNs: Long,
) {

    /** 是否已判定卡死；[reset] 前始终保持 true。 */
    var tripped: Boolean = false
        private set

    private var firstWriteNs = Long.MIN_VALUE
    private var lastAdvanceNs = Long.MIN_VALUE
    private var lastPlayedFrames = 0L

    /** 流启动/重启时调用，重置所有计时。 */
    fun reset(nowNs: Long) {
        tripped = false
        firstWriteNs = Long.MIN_VALUE
        lastAdvanceNs = nowNs
        lastPlayedFrames = 0L
    }

    /** 每次向泵写入数据后调用，记录首次写入时刻（暖机期的起点）。 */
    fun onWrite(nowNs: Long) {
        if (firstWriteNs == Long.MIN_VALUE) firstWriteNs = nowNs
    }

    /**
     * 每 tick 调用一次，传入当前时间与 [UsbIsoPump.playedFrames]。
     *
     * @return true 表示已判定卡死（调用方应回退到 delegate）。
     */
    fun check(nowNs: Long, playedFrames: Long): Boolean {
        if (tripped) return true
        if (firstWriteNs == Long.MIN_VALUE) return false // 还没开始写
        if (playedFrames > lastPlayedFrames) {
            lastPlayedFrames = playedFrames
            lastAdvanceNs = nowNs
            return false
        }
        if (nowNs - firstWriteNs >= warmupNs && nowNs - lastAdvanceNs >= stallNs) {
            tripped = true
            return true
        }
        return false
    }
}
