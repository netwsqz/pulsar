package com.example.pulsar.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 全局设计 token（工单 01）。
 *
 * 数值量纲取自 Auxio 的 `res/values/dimens.xml`：间距 4/8/12/16/20/24、触摸目标 48/56/64、
 * 圆角 16/20。**只取数值规格，不取代码**（Auxio 是 GPL-3.0 + View/XML 架构，见 ADR-0004）。
 *
 * 为什么是普通常量、不做 CompositionLocal：这些是 dp 常量，与明暗主题无关
 * （Auxio 自身也没有 `values-night/dimens.xml` 这类变体），明暗两套主题下取值本就相同。
 * 将来真出现「夜间要另一套尺寸」的需求再升级，现在不预先搭架子。
 *
 * 字体不受本文件影响：仍走 Pulsar 的 Righteous + Poppins（见 [PulsarTypography]）。
 */
internal object Dimens {
    // 间距（对应 Auxio 的 spacing_tiny / _small / _mid_medium / _medium / _mid_large / _large）
    val SpaceTiny = 4.dp
    val SpaceSmall = 8.dp
    val SpaceMidMedium = 12.dp
    val SpaceMedium = 16.dp
    val SpaceMidLarge = 20.dp
    val SpaceLarge = 24.dp

    // 触摸目标（对应 size_touchable_small / _medium / _large）
    val TouchTargetSmall = 48.dp
    val TouchTargetMedium = 56.dp
    val TouchTargetLarge = 64.dp

    // 圆角（对应 m3_shape_corners_large 16，以及播放面板 / BottomSheet 特调的 20）
    val CornerLarge = 16.dp
    val CornerExtraLarge = 20.dp

    // 外壳（工单 01）：浮层边距、玻璃岛、播放条与页面底部留白。
    // 这些是「页面骨架」的尺寸，一处定义、全 App 一致；组件内部的小尺寸（图标多大、
    // 进度线多粗）仍留在组件里，不往这里搬。播放条封面属于外壳规格，所以在这里。

    /** 浮层（玻璃岛 / 迷你播放条 / Snackbar / 音量胶囊 / 多选条）的左右外边距。 */
    val FloatingHorizontalMargin = 12.dp

    /** 浮层与屏幕边缘、浮层之间的垂直外边距。 */
    val FloatingVerticalMargin = 8.dp

    /** 顶部玻璃岛高度（不含上下外边距）。 */
    val GlassIslandHeight = 56.dp

    /** 玻璃岛占位高度 = 岛高 + 上下外边距。列表的第一项从这里往下排，避免钻到岛下面。 */
    val GlassIslandTopSpacing = GlassIslandHeight + FloatingVerticalMargin * 2

    /** 迷你播放条折叠态高度。浮层锚点栈（工单 04）首帧拿不到实测高度时用它兜底。 */
    val PlayerDockHeight = 64.dp

    /** 多选操作条高度：规格 §2 要求与玻璃岛同高，所以直接跟随岛高，不另写数字。 */
    val SelectionBarHeight = GlassIslandHeight

    /** 迷你播放条封面尺寸。 */
    val PlayerDockCover = 40.dp

    /** 所有页面滚动容器的底部留白：保证最后一项能滚到迷你播放条上方。 */
    val BottomContentInset = 96.dp

    /** 队列行的最小高度（触摸目标，工单 05 消费）。 */
    val QueueRowMinHeight = 48.dp

    /** 长按菜单的安全区与内容内边距（阴影需要同宽的安全区，两处共用这一个值）。 */
    val MenuEdgePadding = 16.dp

    // 设置页配色预设色块（工单 13）：一行最多 8 个，窄屏由 FlowRow 自动换行
    val ThemeSwatch = 36.dp
    val ThemeSwatchBorder = 1.dp
    val ThemeSwatchBorderSelected = 2.dp
    val ThemeSwatchCheck = 20.dp
}
