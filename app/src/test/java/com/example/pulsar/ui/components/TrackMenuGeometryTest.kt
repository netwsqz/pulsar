package com.example.pulsar.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长按菜单摆放规则（工单 15 / spec §10）：菜单卡片连同阴影安全区都不能越出窗口。
 *
 * 这条规则以前由 `Popup` 的 `PopupPositionProvider` 现算、只有真机贴着屏幕边缘长按才看得出问题；
 * 提到主窗口 overlay 后抽成纯函数，在这里把四边与居中的边界钉死。
 */
class TrackMenuGeometryTest {

    private val window = IntSize(1080, 1920)
    private val card = IntSize(240, 4 * 48)
    private val pad = 16

    @Test
    fun `触点居中时卡片垂直居中于触点`() {
        val topLeft = menuTopLeft(IntOffset(500, 960), card, window, pad)

        // 横向贴触点：卡片左缘 = 触点 x
        assertEquals(500, topLeft.x)
        // 纵向居中：上缘 = 触点 - 半个卡片
        assertEquals(960 - card.height / 2, topLeft.y)
    }

    @Test
    fun `贴左缘时留足阴影安全区`() {
        val topLeft = menuTopLeft(IntOffset(0, 960), card, window, pad)

        assertEquals(pad, topLeft.x)
    }

    @Test
    fun `贴右缘时夹回可用区`() {
        val topLeft = menuTopLeft(IntOffset(window.width, 960), card, window, pad)

        assertEquals(window.width - card.width - pad, topLeft.x)
    }

    @Test
    fun `贴上缘时留足阴影安全区`() {
        val topLeft = menuTopLeft(IntOffset(500, 0), card, window, pad)

        assertEquals(pad, topLeft.y)
    }

    @Test
    fun `贴下缘时夹回可用区`() {
        val topLeft = menuTopLeft(IntOffset(500, window.height), card, window, pad)

        assertEquals(window.height - card.height - pad, topLeft.y)
    }

    @Test
    fun `窗口比菜单还小时收口到安全区`() {
        val tiny = IntSize(100, 100)

        val topLeft = menuTopLeft(IntOffset(9999, 9999), card, tiny, pad)

        assertEquals(pad, topLeft.x)
        assertEquals(pad, topLeft.y)
    }

    @Test
    fun `贴顶时不钻到状态栏下面`() {
        val topInset = 96 // 状态栏

        val topLeft = menuTopLeft(IntOffset(500, 0), card, window, pad, topInsetPx = topInset)

        assertEquals(topInset + pad, topLeft.y)
    }

    @Test
    fun `贴底时不钻到导航栏下面`() {
        val bottomInset = 72 // 手势导航条

        val topLeft = menuTopLeft(IntOffset(500, window.height), card, window, pad, bottomInsetPx = bottomInset)

        assertEquals(window.height - bottomInset - card.height - pad, topLeft.y)
    }
}
