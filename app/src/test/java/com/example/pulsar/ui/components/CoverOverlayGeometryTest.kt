package com.example.pulsar.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面叠加物的几何规则（工单 14 / spec §9）：播放指示条的高度映射与封面底部渐变的高度。
 *
 * 这两条都是「以前埋在 draw 代码里、只有真机肉眼能验」的数值规则，拆成纯函数就是为了在这里钉死：
 * · 暂停时的静止高度必须和播放动画走**同一条**映射（4/9/6dp 不是另写一套常量），否则改一处忘一处，
 *   暂停/播放一按就跳高度；
 * · 40dp 的小封面渐变必须按比例缩小（10dp，而不是固定 32dp 压掉大半块），大封面必须封顶 32dp。
 */
class CoverOverlayGeometryTest {

    // ===== 播放指示条：进度 → 高度 =====

    @Test
    fun `进度零是条的最小高度`() {
        assertEquals(4f, playingBarHeight(0f).value, 1e-4f)
    }

    @Test
    fun `进度一是条的最大高度`() {
        assertEquals(14f, playingBarHeight(1f).value, 1e-4f)
    }

    @Test
    fun `进度线性插值到高度`() {
        assertEquals(9f, playingBarHeight(0.5f).value, 1e-4f) // 4 + 10 × 0.5
        assertEquals(6f, playingBarHeight(0.2f).value, 1e-4f) // 4 + 10 × 0.2
    }

    @Test
    fun `越界进度被夹回零到一`() {
        assertEquals(4f, playingBarHeight(-3f).value, 1e-4f)
        assertEquals(14f, playingBarHeight(7f).value, 1e-4f)
    }

    @Test
    fun `暂停时三根条静止在 4 9 6 dp`() {
        val heights = pausedPlayingBarProgress.map { playingBarHeight(it).value }

        assertEquals(PlayingBarCount, heights.size)
        assertEquals(4f, heights[0], 1e-4f)
        assertEquals(9f, heights[1], 1e-4f)
        assertEquals(6f, heights[2], 1e-4f)
    }

    @Test
    fun `指示器画布宽度是三根条加两个间隙`() {
        assertEquals(13f, PlayingBarsWidth.value, 1e-4f) // 3×3 + 2×2
    }

    // ===== 封面底部渐变：封面高度 → 渐变高度 =====

    @Test
    fun `渐变高度是封面的四分之一`() {
        assertEquals(10f, coverScrimHeight(40.dp).value, 1e-4f) // 迷你条 40dp 封面
        assertEquals(12f, coverScrimHeight(48.dp).value, 1e-4f) // 列表行 48dp 封面
        assertEquals(16f, coverScrimHeight(64.dp).value, 1e-4f) // 队列面板 64dp 封面
    }

    @Test
    fun `大封面的渐变封顶 32dp`() {
        assertEquals(32f, coverScrimHeight(128.dp).value, 1e-4f) // 128 × 0.25 刚好到顶
        assertEquals(32f, coverScrimHeight(140.dp).value, 1e-4f) // 发现页横滑卡片
        assertEquals(32f, coverScrimHeight(320.dp).value, 1e-4f) // 全屏播放页大封面
    }

    @Test
    fun `封面高为零时渐变也为零`() {
        assertEquals(0f, coverScrimHeight(0.dp).value, 1e-4f)
    }
}
