package com.example.pulsar.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Pulsar 设计系统常量
 * 玻璃态效果、圆角、间距等
 */

object PulsarDimensions {
    // ===== 圆角 =====
    val CornerSmall = 8.dp
    val CornerMedium = 12.dp
    val CornerLarge = 16.dp
    val CornerExtraLarge = 24.dp
    val CornerFull = 9999.dp // 完全圆角（胶囊形）

    // ===== 间距 =====
    val SpacingExtraSmall = 4.dp
    val SpacingSmall = 8.dp
    val SpacingMedium = 16.dp
    val SpacingLarge = 24.dp
    val SpacingExtraLarge = 32.dp

    // ===== 触摸目标 =====
    val TouchTargetMin = 48.dp // Material 最小触摸目标

    // ===== 玻璃效果 =====
    val GlassBlurRadius = 15.dp // 背景模糊半径
    val GlassOpacity = 0.8f // 玻璃卡片不透明度（80%）
    val GlassBorderWidth = 1.dp // 玻璃边框宽度

    // ===== 阴影（配合玻璃效果的轻量阴影） =====
    val ElevationSmall = 2.dp
    val ElevationMedium = 4.dp
    val ElevationLarge = 8.dp
}
