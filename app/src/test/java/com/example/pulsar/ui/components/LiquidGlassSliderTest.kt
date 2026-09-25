package com.example.pulsar.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 液态玻璃进度条的两条纯逻辑（sliderValueForDragX / sliderFraction）测试。
 * 拖动换算与钳制是 UI 里唯一能离线判定的部分，先钉死规则，组合体里只做「取尺寸 → 调函数 → 画」。
 * 防御项（轨道宽为 0/负/NaN、x 为 NaN/无穷、时长未知导致 range 退化）一律给安全值，不让脏值污染父层的拖动状态。
 */
class LiquidGlassSliderTest {

    // ===== sliderValueForDragX：拖动 x 像素 → 进度值 =====

    @Test
    fun `x 在轨道左端返回下界`() {
        assertEquals(0f, sliderValueForDragX(0f, 300f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `x 在轨道中点返回区间中点`() {
        assertEquals(30f, sliderValueForDragX(150f, 300f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `x 在轨道右端返回上界`() {
        assertEquals(60f, sliderValueForDragX(300f, 300f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `x 超出左右两端都钳制在区间内`() {
        assertEquals(0f, sliderValueForDragX(-50f, 300f, 0f, 60f), 1e-4f)
        assertEquals(60f, sliderValueForDragX(999f, 300f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `非零下界的区间按比例映射`() {
        assertEquals(10f, sliderValueForDragX(0f, 300f, 10f, 20f), 1e-4f)
        assertEquals(15f, sliderValueForDragX(150f, 300f, 10f, 20f), 1e-4f)
        assertEquals(20f, sliderValueForDragX(300f, 300f, 10f, 20f), 1e-4f)
    }

    @Test
    fun `轨道宽度为0或负返回下界`() {
        assertEquals(0f, sliderValueForDragX(100f, 0f, 0f, 60f), 1e-4f)
        assertEquals(0f, sliderValueForDragX(100f, -300f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `x 或轨道宽度为非有限值返回下界`() {
        assertEquals(0f, sliderValueForDragX(Float.NaN, 300f, 0f, 60f), 1e-4f)
        assertEquals(0f, sliderValueForDragX(Float.POSITIVE_INFINITY, 300f, 0f, 60f), 1e-4f)
        assertEquals(0f, sliderValueForDragX(Float.NEGATIVE_INFINITY, 300f, 0f, 60f), 1e-4f)
        assertEquals(0f, sliderValueForDragX(100f, Float.NaN, 0f, 60f), 1e-4f)
    }

    @Test
    fun `非零下界的区间在非法输入时也返回下界`() {
        assertEquals(10f, sliderValueForDragX(100f, 0f, 10f, 20f), 1e-4f)
        assertEquals(10f, sliderValueForDragX(Float.NaN, 300f, 10f, 20f), 1e-4f)
    }

    // ===== sliderFraction：进度值 → 0..1 填充比例 =====

    @Test
    fun `区间中点返回0_5`() {
        assertEquals(0.5f, sliderFraction(30f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `下界返回0上界返回1`() {
        assertEquals(0f, sliderFraction(0f, 0f, 60f), 1e-4f)
        assertEquals(1f, sliderFraction(60f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `越界值被钳制`() {
        assertEquals(0f, sliderFraction(-5f, 0f, 60f), 1e-4f)
        assertEquals(1f, sliderFraction(999f, 0f, 60f), 1e-4f)
    }

    @Test
    fun `非零下界的区间映射为填充比例`() {
        assertEquals(0f, sliderFraction(10f, 10f, 20f), 1e-4f)
        assertEquals(0.5f, sliderFraction(15f, 10f, 20f), 1e-4f)
        assertEquals(1f, sliderFraction(20f, 10f, 20f), 1e-4f)
    }

    @Test
    fun `区间退化为零宽返回0`() {
        // 时长未知时 range 是 0f..0f，不能除零，也不能让进度条画出长度。
        assertEquals(0f, sliderFraction(0f, 0f, 0f), 1e-4f)
        assertEquals(0f, sliderFraction(5f, 5f, 5f), 1e-4f)
    }

    @Test
    fun `区间上界小于下界返回0`() {
        assertEquals(0f, sliderFraction(30f, 60f, 0f), 1e-4f)
    }

    @Test
    fun `区间或值为非有限值返回0`() {
        assertEquals(0f, sliderFraction(Float.NaN, 0f, 60f), 1e-4f)
        assertEquals(0f, sliderFraction(Float.POSITIVE_INFINITY, 0f, 60f), 1e-4f)
        assertEquals(0f, sliderFraction(30f, Float.NaN, 60f), 1e-4f)
        assertEquals(0f, sliderFraction(30f, 0f, Float.POSITIVE_INFINITY), 1e-4f)
    }
}
