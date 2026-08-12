package com.example.pulsar.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 定时关闭（睡眠定时器）状态。
 * [totalMs] 与 [remainingMs] 都为 0 表示未启用；[fraction] 供进度条展示剩余占比。
 */
data class SleepTimerState(
    val totalMs: Long = 0L,
    val remainingMs: Long = 0L
) {
    val isActive: Boolean get() = totalMs > 0

    /** 剩余占比 0f..1f；未启用时为 0f。 */
    val fraction: Float
        get() = if (totalMs > 0) (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * 可测试的定时关闭引擎：按 [start] 传入的时长每秒递减 [remainingMs]，
 * 到 0 时复位状态并调用 [onTimeout]（默认动作是暂停播放）。
 * 重新 [start] 会取消旧定时；[cancel] 立即复位且不触发 [onTimeout]。
 */
class SleepTimerEngine(private val scope: CoroutineScope) {
    private var job: Job? = null
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /** 到点回调，由 [PlaybackService] 注入"暂停播放"。 */
    var onTimeout: (() -> Unit)? = null

    fun start(durationMs: Long) {
        require(durationMs > 0) { "定时时长必须大于 0，收到 $durationMs" }
        job?.cancel()
        _state.value = SleepTimerState(totalMs = durationMs, remainingMs = durationMs)
        job = scope.launch {
            var remaining = durationMs
            while (remaining > 0) {
                val step = minOf(1000L, remaining)
                delay(step)
                remaining -= step
                _state.value = _state.value.copy(remainingMs = remaining)
                if (remaining == 0L) {
                    _state.value = SleepTimerState()
                    onTimeout?.invoke()
                    break
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SleepTimerState()
    }
}

/**
 * 进程级定时关闭单例：播放服务到点暂停播放、UI 读取剩余时间/进度条位置。
 * 与 [PlaybackDiagnostics] 同构——后台播放、Activity 重建时定时照常生效。
 */
object SleepTimer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = SleepTimerEngine(scope)

    val state: StateFlow<SleepTimerState> = engine.state

    /** 到点回调，由 [PlaybackService] 注入/清空。 */
    var onTimeout: (() -> Unit)?
        get() = engine.onTimeout
        set(value) { engine.onTimeout = value }

    fun start(durationMs: Long) = engine.start(durationMs)
    fun cancel() = engine.cancel()
}
