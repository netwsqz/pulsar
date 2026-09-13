package com.example.pulsar.playback.usb

/**
 * 等时泵卡死看门狗。
 *
 * 判断依据：native 驱动（usb_audio_driver.cpp）的 `played_frames_` 计数器**只增不减**
 * ——flush 只重置 ring 读指针（pending_flush_ → ring_read_），从不清零计数。
 * 所以必须用「推进检测」（playedFrames 是否增长）而非「相等检测」。
 *
 * ### 为什么还要看心跳
 *
 * 「播放帧不推进」有两个完全不同的原因：
 *
 * - **泵卡死**：事件线程还在被调度（心跳在涨），但等时传输不再完成
 *   （典型场景：某些 xHCI 控制器/驱动组合的等时端点楔住）。这时必须回退。
 * - **CPU 饥饿**：事件线程压根没拿到 CPU（心跳也不涨），播放帧自然不涨。
 *   前台重负载下的常态，**会自愈**——线程恢复后一口气补完积压的 URB，
 *   playedFrames 跳一下。这时回退是有害的：flushRing + 重启流会丢弃 ring 里
 *   约 1 秒未播音频，听感即「断音/跳秒」。
 *
 * 因此用 `heartbeat`（事件线程每轮循环 +1）区分两者：
 * - 心跳在涨、播放帧停滞超过 [stallNs] → 泵真卡死 → 判死。
 * - 心跳也停了 → 饥饿，继续等；只有连心跳都停了 [starvationCapNs]（硬上限，
 *   防真死锁时无限等待）才判死。
 *
 * 另有 [loopExited] 明确信号：native 事件循环已退出（出错停泵）→ 立即判死，
 * 不必等任何窗口。
 *
 * ### 暖机
 *
 * 从首次写入起 [warmupNs] 内即使无推进也不判定：刚 `start_stream` 时等时 URBs
 * 首次提交有延迟、设备还没开始消费，此刻 playedFrames 停滞是正常的。
 *
 * 纯时间逻辑，不依赖 Android 时钟，可单元测试。`nowNs` 由调用方注入
 * （SystemClock.elapsedRealtimeNanos()）。
 */
class IsoPumpWatchdog(
    private val warmupNs: Long,
    private val stallNs: Long,
    private val starvationCapNs: Long = DEFAULT_STARVATION_CAP_NS,
) {

    /** 是否已判定卡死；[reset] 前始终保持 true。 */
    var tripped: Boolean = false
        private set

    private var firstWriteNs = Long.MIN_VALUE
    private var lastAdvanceNs = Long.MIN_VALUE
    private var lastPlayedFrames = 0L
    private var lastHeartbeat = Long.MIN_VALUE
    private var lastHeartbeatNs = Long.MIN_VALUE

    /** 流启动/重启时调用，重置所有计时。 */
    fun reset(nowNs: Long) {
        tripped = false
        firstWriteNs = Long.MIN_VALUE
        lastAdvanceNs = nowNs
        lastPlayedFrames = 0L
        lastHeartbeat = Long.MIN_VALUE
        lastHeartbeatNs = nowNs
    }

    /** 每次向泵写入数据后调用，记录首次写入时刻（暖机期的起点）。 */
    fun onWrite(nowNs: Long) {
        if (firstWriteNs == Long.MIN_VALUE) firstWriteNs = nowNs
    }

    /**
     * 每 tick 调用一次，传入当前时间、[UsbIsoPump.playedFrames] 与 native 的
     * 事件线程心跳/退出标志。
     *
     * @param heartbeat 事件线程心跳计数（每轮事件循环 +1），只增。
     * @param loopExited native 事件循环是否已退出（出错停泵或正常收尾）。
     * @return true 表示已判定卡死（调用方应回退到 delegate）。
     */
    fun check(nowNs: Long, playedFrames: Long, heartbeat: Long, loopExited: Boolean): Boolean {
        if (tripped) return true
        // 事件循环退出 = 泵确实没了，不必等任何窗口。
        if (loopExited) {
            tripped = true
            return true
        }
        if (heartbeat != lastHeartbeat) {
            lastHeartbeat = heartbeat
            lastHeartbeatNs = nowNs
        }
        if (firstWriteNs == Long.MIN_VALUE) return false // 还没开始写
        if (playedFrames > lastPlayedFrames) {
            lastPlayedFrames = playedFrames
            lastAdvanceNs = nowNs
            return false
        }
        if (nowNs - firstWriteNs < warmupNs) return false // 暖机期
        if (nowNs - lastAdvanceNs < stallNs) return false // 推进窗口还没耗尽
        // 播放帧停滞已超过 stallNs，靠心跳判断原因：
        if (nowNs - lastHeartbeatNs < stallNs) {
            // 线程近期跑过 → 泵真卡死
            tripped = true
            return true
        }
        // 线程也停了 = CPU 饥饿，等它自愈；硬上限防真死锁。
        if (nowNs - lastHeartbeatNs >= starvationCapNs) {
            tripped = true
            return true
        }
        return false
    }

    companion object {
        /**
         * 心跳也停止多久才认定「不是饥饿，是线程真出问题」的硬上限。
         * 取 6 秒：比看门狗原有的 1.5s 卡死窗口宽松得多，给短暂饥饿充分的自愈机会；
         * 又足够短，不至于在真死锁时让用户听几分钟空白。
         */
        const val DEFAULT_STARVATION_CAP_NS = 6_000_000_000L
    }
}
