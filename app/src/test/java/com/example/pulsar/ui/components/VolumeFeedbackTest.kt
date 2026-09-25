package com.example.pulsar.ui.components

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音量浮窗的显示窗口：按一次音量键点亮、静置 [VolumeFeedbackController] 的保持时间后自动熄灭。
 *
 * 最要紧的一条是**重置计时**：用户可以连按音量键，浮窗必须一直亮着、不能按一下闪一下
 * （中间熄灭再点亮会读成「卡了」）。这条只有靠计时器在每次新调整时被取消重建才成立，
 * 也正是本类唯一值得单测的行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VolumeFeedbackTest {

    private val holdMillis = 1_500L

    @Test
    fun `初始不显示`() = runTest {
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)

        assertFalse(feedback.state.value.visible)
    }

    @Test
    fun `show 之后立刻显示并带出音量值`() = runTest {
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)

        feedback.show(65)

        assertTrue(feedback.state.value.visible)
        assertEquals(65, feedback.state.value.percent)
    }

    @Test
    fun `静置到保持时间后自动熄灭`() = runTest {
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)
        feedback.show(65)

        advanceTimeBy(holdMillis - 1)
        runCurrent()
        assertTrue("还没到保持时间，不该提前熄灭", feedback.state.value.visible)

        advanceTimeBy(2)
        runCurrent()
        assertFalse("过了保持时间应自动熄灭", feedback.state.value.visible)
    }

    @Test
    fun `保持时间内再次调整会重置计时`() = runTest {
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)
        feedback.show(65)

        advanceTimeBy(holdMillis - 1)
        runCurrent()
        feedback.show(70) // 临近熄灭前又按了一下

        advanceTimeBy(holdMillis - 1)
        runCurrent()
        assertTrue("第二次调整应重新计时，浮窗不能中途闪断", feedback.state.value.visible)
        assertEquals(70, feedback.state.value.percent)

        advanceTimeBy(2)
        runCurrent()
        assertFalse(feedback.state.value.visible)
    }

    @Test
    fun `音量值没变也重新点亮并重置计时`() = runTest {
        // 连按到 100 上限后再按，目标值不变但确实是新的一次按键，浮窗同样要续命。
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)
        feedback.show(100)

        advanceTimeBy(holdMillis - 1)
        runCurrent()
        feedback.show(100)

        advanceTimeBy(holdMillis - 1)
        runCurrent()
        assertTrue("值相同也要重置计时", feedback.state.value.visible)
    }

    @Test
    fun `dismiss 立即熄灭`() = runTest {
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)
        feedback.show(65)

        feedback.dismiss()

        assertFalse(feedback.state.value.visible)
    }

    @Test
    fun `dismiss 之后还能再次点亮`() = runTest {
        // 计时协程是被取消的，dismiss 后不能把状态机卡死。
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)
        feedback.show(65)
        feedback.dismiss()

        feedback.show(70)

        assertTrue(feedback.state.value.visible)
        assertEquals(70, feedback.state.value.percent)
    }

    @Test
    fun `熄灭后音量值保留供淡出动画使用`() = runTest {
        val feedback = VolumeFeedbackController(backgroundScope, holdMillis)
        feedback.show(65)

        advanceTimeBy(holdMillis + 1)
        runCurrent()

        assertFalse(feedback.state.value.visible)
        assertEquals("淡出动画期间还要显示这个值，不能清零", 65, feedback.state.value.percent)
    }
}
