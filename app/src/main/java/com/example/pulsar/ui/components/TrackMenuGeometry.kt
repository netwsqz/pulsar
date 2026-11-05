package com.example.pulsar.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 长按菜单的摆放规则（工单 15 / spec §10）。
 *
 * 菜单从 `Popup` 提到主窗口 overlay 之后，位置不再由框架代劳，得自己算：贴着长按的触点弹出，
 * 但**连同外圈阴影安全区一起**不能越过窗口边缘，否则菜单会被裁掉一角或整个翻出屏幕。
 *
 * 抽成纯函数是因为这条夹取规则只有真机贴着屏幕边缘长按才看得出问题，放在组合里就是「肉眼看一眼」
 * ——现在能在单测里把边界钉死。
 */

/**
 * 菜单**卡片**（不含阴影安全区）左上角在窗口坐标系里的位置。
 *
 * @param touchPoint 长按触点（窗口坐标）。
 * @param menuSize 菜单卡片尺寸（不含 [edgePaddingPx] 的阴影安全区）。
 * @param windowSize 主窗口尺寸。
 * @param edgePaddingPx 阴影安全区：卡片四周都要留出这么多，所以可用区比窗口小两圈。
 * @param topInsetPx 状态栏高度。主窗口是 edge-to-edge，不避开就会把菜单画到状态栏底下。
 * @param bottomInsetPx 导航栏高度。
 *
 * 横向：卡片左缘贴住触点弹出（手指落在卡片左侧，菜单往右展开）；越界就夹回可用区。
 * 纵向：卡片**垂直居中**于触点（长按行多半在屏幕中部，居中比顶对齐更少越界）。
 * 窗口比菜单还小时可用区收口到最小边距（不再往上顶），菜单会被裁，这是无解的极端情况。
 */
internal fun menuTopLeft(
    touchPoint: IntOffset,
    menuSize: IntSize,
    windowSize: IntSize,
    edgePaddingPx: Int,
    topInsetPx: Int = 0,
    bottomInsetPx: Int = 0
): IntOffset {
    val minX = edgePaddingPx
    val minY = topInsetPx + edgePaddingPx
    // 卡片右缘 / 下缘（含安全区）不得越过窗口：x + width + edge <= window。
    val maxX = (windowSize.width - menuSize.width - edgePaddingPx).coerceAtLeast(minX)
    val maxY = (windowSize.height - bottomInsetPx - menuSize.height - edgePaddingPx)
        .coerceAtLeast(minY)
    return IntOffset(
        x = touchPoint.x.coerceIn(minX, maxX),
        y = (touchPoint.y - menuSize.height / 2).coerceIn(minY, maxY)
    )
}
