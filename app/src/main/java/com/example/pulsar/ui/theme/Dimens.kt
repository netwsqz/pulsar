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
}
