package com.example.pulsar.ui.components

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
 * 音量浮窗的显示状态。
 *
 * [visible] 为 false 时 [percent] **仍然保留**——淡出动画期间还得显示着那个数字，
 * 清零会让浮窗在消失前一瞬跳成 0。
 */
data class VolumeFeedbackState(
    val percent: Int = 0,
    val visible: Boolean = false
)

/**
 * 音量浮窗的显示窗口：一次音量调整点亮浮窗，静置 [holdMillis] 不灭就不管，期间再来一次则
 * **重置**计时。
 *
 * 重置计时是这里唯一有分支的行为，也是必须做对的一条：连续按音量键时浮窗要一直亮着，
 * 若每次按键各自起一份计时，就会在两次按键的间隙里闪灭再点亮，读起来像卡顿。
 *
 * 与 [com.example.pulsar.playback.SleepTimerEngine] 同构（协程计时 + StateFlow），
 * 但这里没有周期推进，只有「点亮 → 等待 → 熄灭」。
 */
class VolumeFeedbackController(
    private val scope: CoroutineScope,
    private val holdMillis: Long = DefaultHoldMillis
) {

    companion object {
        /** 浮窗保持时间。够看清数字，又不至于在调节结束后赖着不走。 */
        const val DefaultHoldMillis = 1_500L
    }

    private val _state = MutableStateFlow(VolumeFeedbackState())
    val state: StateFlow<VolumeFeedbackState> = _state.asStateFlow()

    private var job: Job? = null

    /** 点亮浮窗并显示 [percent]，同时把熄灭计时重置。 */
    fun show(percent: Int) {
        job?.cancel()
        // 先落状态再起计时：show 返回时状态就必须已经可见，调用方（输入分发那条路）不等协程调度。
        _state.value = VolumeFeedbackState(percent = percent, visible = true)
        job = scope.launch {
            delay(holdMillis)
            _state.value = _state.value.copy(visible = false)
        }
    }

    /** 立刻熄灭，不改变 [VolumeFeedbackState.percent]。 */
    fun dismiss() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(visible = false)
    }
}

/**
 * 进程级音量浮窗状态：UI 层写入（音量键成功调整后），浮窗组件读取。
 * 与 [com.example.pulsar.playback.SleepTimer] 同构——是一个从输入路径到展示层的事件，
 * 与播放服务无关，但同样需要跨 Activity 重建存活（重建时用户可能正按着音量键）。
 */
object VolumeFeedback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = VolumeFeedbackController(scope)

    val state: StateFlow<VolumeFeedbackState> = controller.state

    fun show(percent: Int) = controller.show(percent)
    fun dismiss() = controller.dismiss()
}
