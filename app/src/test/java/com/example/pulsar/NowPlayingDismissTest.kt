package com.example.pulsar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏播放页「下滑收起」判定纯函数（shouldDismissNowPlaying）的测试：
 * 拖动位移超过屏高 15% 才收起；阈值边界前后、零值、负值与非法输入（NaN / 无穷 / 非正屏高）一律不收起。
 * 一期先把阈值规则钉死，后续手势关闭批次直接调用该函数。
 */
class NowPlayingDismissTest {

    // ===== 正常路径 =====

    @Test
    fun `大幅下滑超过阈值判定收起`() {
        assertTrue(shouldDismissNowPlaying(600f, 1000f))
    }

    @Test
    fun `小幅下滑未到阈值不收起`() {
        assertFalse(shouldDismissNowPlaying(50f, 1000f))
    }

    // ===== 阈值边界（屏高 15%）=====

    @Test
    fun `恰好等于阈值不收起_必须严格超过`() {
        // 两侧用同一套 Float 运算（1000f * 0.15f），避免浮点表示差异造成边界漂移。
        val screenHeight = 1000f
        val thresholdOffset = screenHeight * 0.15f
        assertFalse(shouldDismissNowPlaying(thresholdOffset, screenHeight))
    }

    @Test
    fun `略高于阈值判定收起`() {
        val screenHeight = 1000f
        val thresholdOffset = screenHeight * 0.15f
        assertTrue(shouldDismissNowPlaying(thresholdOffset + 0.5f, screenHeight))
    }

    @Test
    fun `略低于阈值不收起`() {
        val screenHeight = 1000f
        val thresholdOffset = screenHeight * 0.15f
        assertFalse(shouldDismissNowPlaying(thresholdOffset - 0.5f, screenHeight))
    }

    // ===== 防御：零值 / 负值 / 非法输入 =====

    @Test
    fun `位移为0不收起`() {
        assertFalse(shouldDismissNowPlaying(0f, 1000f))
    }

    @Test
    fun `位移为负不收起`() {
        assertFalse(shouldDismissNowPlaying(-300f, 1000f))
    }

    @Test
    fun `屏高为0或负数不收起`() {
        assertFalse(shouldDismissNowPlaying(600f, 0f))
        assertFalse(shouldDismissNowPlaying(600f, -1000f))
    }

    @Test
    fun `NaN或无穷大输入一律不收起`() {
        assertFalse(shouldDismissNowPlaying(Float.NaN, 1000f))
        assertFalse(shouldDismissNowPlaying(600f, Float.NaN))
        assertFalse(shouldDismissNowPlaying(Float.POSITIVE_INFINITY, 1000f))
        assertFalse(shouldDismissNowPlaying(600f, Float.POSITIVE_INFINITY))
        assertFalse(shouldDismissNowPlaying(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY))
    }
}
