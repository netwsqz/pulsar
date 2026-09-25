package com.example.pulsar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全屏播放页「页指示器」两条纯逻辑的测试（indicatorOffsetFraction / indicatorStretch）。
 * 指示器要跟着横滑**连续**移动（不是到页才跳），并且滑到两页中间时把滑块拉长一点，做出「液态」的观感。
 * 边界规则先钉死：首尾页不外溢、页数为 0 安全、非法输入回落静止态。
 */
class NowPlayingIndicatorTest {

    // ===== indicatorOffsetFraction：滑块位置（单位：页）=====

    @Test
    fun `停在某页时位置就是该页序号`() {
        assertEquals(1f, indicatorOffsetFraction(currentPage = 1, pageOffsetFraction = 0f, pageCount = 3), 1e-4f)
    }

    @Test
    fun `向右滑到两页中间时位置在两点五`() {
        assertEquals(1.5f, indicatorOffsetFraction(currentPage = 1, pageOffsetFraction = 0.5f, pageCount = 3), 1e-4f)
    }

    @Test
    fun `向左滑到两页中间时位置在零点五`() {
        assertEquals(0.5f, indicatorOffsetFraction(currentPage = 1, pageOffsetFraction = -0.5f, pageCount = 3), 1e-4f)
    }

    @Test
    fun `首页再往左不外溢`() {
        assertEquals(0f, indicatorOffsetFraction(currentPage = 0, pageOffsetFraction = -0.4f, pageCount = 3), 1e-4f)
    }

    @Test
    fun `末页再往右不外溢`() {
        assertEquals(2f, indicatorOffsetFraction(currentPage = 2, pageOffsetFraction = 0.4f, pageCount = 3), 1e-4f)
    }

    @Test
    fun `单页时位置恒为0`() {
        assertEquals(0f, indicatorOffsetFraction(currentPage = 0, pageOffsetFraction = 0.5f, pageCount = 1), 1e-4f)
    }

    @Test
    fun `页数非正返回0`() {
        assertEquals(0f, indicatorOffsetFraction(currentPage = 0, pageOffsetFraction = 0f, pageCount = 0), 1e-4f)
        assertEquals(0f, indicatorOffsetFraction(currentPage = 0, pageOffsetFraction = 0f, pageCount = -1), 1e-4f)
    }

    @Test
    fun `偏移为非有限值时回落到当前页`() {
        assertEquals(1f, indicatorOffsetFraction(currentPage = 1, pageOffsetFraction = Float.NaN, pageCount = 3), 1e-4f)
        assertEquals(1f, indicatorOffsetFraction(currentPage = 1, pageOffsetFraction = Float.POSITIVE_INFINITY, pageCount = 3), 1e-4f)
        assertEquals(1f, indicatorOffsetFraction(currentPage = 1, pageOffsetFraction = Float.NEGATIVE_INFINITY, pageCount = 3), 1e-4f)
    }

    // ===== indicatorStretch：滑动中的拉长系数 =====

    @Test
    fun `静止时拉伸系数为1`() {
        assertEquals(1f, indicatorStretch(0f), 1e-4f)
    }

    @Test
    fun `滑到两页正中间时按最大拉伸拉长`() {
        assertEquals(1.5f, indicatorStretch(0.5f), 1e-4f)
        assertEquals(1.5f, indicatorStretch(-0.5f), 1e-4f)
    }

    @Test
    fun `四分之一偏移按比例拉长`() {
        assertEquals(1.25f, indicatorStretch(0.25f), 1e-4f)
    }

    @Test
    fun `偏移超过半个页时按最大值封顶`() {
        assertEquals(1.5f, indicatorStretch(1f), 1e-4f)
        assertEquals(1.5f, indicatorStretch(-3f), 1e-4f)
    }

    @Test
    fun `可自定义最大拉伸量`() {
        assertEquals(2f, indicatorStretch(0.5f, maxStretch = 1f), 1e-4f)
    }

    @Test
    fun `偏移为非有限值时按静止处理`() {
        assertEquals(1f, indicatorStretch(Float.NaN), 1e-4f)
        assertEquals(1f, indicatorStretch(Float.POSITIVE_INFINITY), 1e-4f)
        assertEquals(1f, indicatorStretch(Float.NEGATIVE_INFINITY), 1e-4f)
    }
}
