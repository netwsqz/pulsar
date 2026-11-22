package com.example.pulsar

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏播放页横屏适配的纯逻辑：
 * - [isLandscapeLayout]：按**窗口宽高**判定版式（不是读 `orientation` 字段）——分屏 / 折叠屏
 *   展开后窗口变宽，也该走横版骨架。
 * - [nowPlayingRotationTarget]：页面内旋转按钮的目标方向（竖版锁横屏 / 横版锁竖屏）。
 * - [landscapeSplit]：横版左右两栏的宽度分配（左封面 / 右歌词）。
 */
class NowPlayingLayoutTest {

    // ===== isLandscapeLayout =====

    @Test
    fun `宽度大于高度判定为横屏版式`() {
        assertTrue(isLandscapeLayout(widthDp = 800, heightDp = 360))
    }

    @Test
    fun `高度大于宽度判定为竖屏版式`() {
        assertFalse(isLandscapeLayout(widthDp = 360, heightDp = 800))
    }

    @Test
    fun `正方形窗口按竖屏处理`() {
        assertFalse(isLandscapeLayout(widthDp = 600, heightDp = 600))
    }

    @Test
    fun `零或负尺寸不判定为横屏`() {
        assertFalse(isLandscapeLayout(widthDp = 0, heightDp = 0))
        assertFalse(isLandscapeLayout(widthDp = -100, heightDp = -100))
        assertFalse(isLandscapeLayout(widthDp = 0, heightDp = 800))
    }

    // ===== nowPlayingRotationTarget =====

    @Test
    fun `竖版下旋转按钮请求锁定横屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            nowPlayingRotationTarget(currentlyLandscape = false)
        )
    }

    @Test
    fun `横版下旋转按钮请求锁定竖屏`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            nowPlayingRotationTarget(currentlyLandscape = true)
        )
    }

    // ===== landscapeSplit：左右两栏各占多少 =====

    @Test
    fun `横版按四六分栏_左封面右歌词`() {
        val split = landscapeSplit(1000)
        assertEquals(420f, split.leftWidthDp, 0.01f)
        assertEquals(580f, split.rightWidthDp, 0.01f)
    }

    @Test
    fun `两栏之和等于可用宽度`() {
        val split = landscapeSplit(640)
        assertEquals(640f, split.leftWidthDp + split.rightWidthDp, 0.01f)
    }

    @Test
    fun `宽度为零或负时不产生负的栏宽`() {
        val zero = landscapeSplit(0)
        assertEquals(0f, zero.leftWidthDp, 0.01f)
        assertEquals(0f, zero.rightWidthDp, 0.01f)
        val negative = landscapeSplit(-200)
        assertEquals(0f, negative.leftWidthDp, 0.01f)
        assertEquals(0f, negative.rightWidthDp, 0.01f)
    }

    // ===== orientationToRestoreOnClose：收起全屏页时要还原的方向 =====
    // 回归用例：曾经拿 -1 当「还没锁过」的哨兵，而 UNSPECIFIED 本身就是 -1，
    // 导致收起时永远判断不到「锁过」，App 永久卡在横屏。

    @Test
    fun `第一次锁时记下当前方向_即使它就是UNSPECIFIED`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientationToRestoreOnClose(
                previous = null,
                currentOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            )
        )
    }

    @Test
    fun `记下UNSPECIFIED后仍能被识别为已记录`() {
        val recorded = orientationToRestoreOnClose(
            previous = null,
            currentOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        )
        // 关键：记录值非 null，调用方据此判断「锁过、收起时要还原」。
        assertEquals(
            recorded,
            orientationToRestoreOnClose(
                previous = recorded,
                currentOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            )
        )
    }

    @Test
    fun `再次锁定时不覆盖进页前的方向`() {
        val first = orientationToRestoreOnClose(previous = null, currentOrientation = 7)
        val second = orientationToRestoreOnClose(previous = first, currentOrientation = 11)
        assertEquals(7, second)
    }

    @Test
    fun `进页前的方向是具体值时原样记下`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            orientationToRestoreOnClose(
                previous = null,
                currentOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            )
        )
    }
}
