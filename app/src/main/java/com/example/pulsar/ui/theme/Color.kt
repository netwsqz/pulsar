package com.example.pulsar.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Pulsar 液态玻璃风格配色方案
 * 设计理念：浅色、透明、现代、空气感
 */

// ===== 品牌色 - 音乐主题的渐变蓝紫 =====
val BrandPrimary = Color(0xFF6366F1) // 靛蓝 - 主品牌色
val BrandSecondary = Color(0xFF8B5CF6) // 紫色 - 次要强调
val BrandAccent = Color(0xFF10B981) // 翠绿 - 播放/活跃状态

// ===== 浅色模式基础色 =====
val LightBackground = Color(0xFFF8FAFC) // 极浅灰蓝 - 主背景
val LightSurface = Color(0xFFFFFFFF) // 纯白 - 卡片表面（配合玻璃效果）
val LightSurfaceVariant = Color(0xFFF1F5F9) // 浅灰蓝 - 占位/次要表面

val LightOnBackground = Color(0xFF0F172A) // 深蓝黑 - 主文本
val LightOnSurface = Color(0xFF1E293B) // 深灰蓝 - 卡片文本
val LightOnSurfaceVariant = Color(0xFF64748B) // 中灰蓝 - 次要文本

val LightOutline = Color(0xFFE2E8F0) // 极浅边框
val LightOutlineVariant = Color(0xFFCBD5E1) // 浅边框

// ===== 功能色 =====
val ErrorRed = Color(0xFFEF4444)

/**
 * 手动配色预设（工单 13，取自 Auxio 的强调色集合，去掉 Dynamic）。
 * 这里存的是**高饱和种子**，推导阶段会统一钳饱和度（ADR-0005），所以不必预先调柔。
 * Indigo 必须与 [BrandPrimary] 同值：没选过预设时用的就是默认色板，用户点它不该有观感跳变。
 */
/** 预设色的中文名：色块本身只有颜色，这个名字给无障碍读屏用（也是设置页的语义标签）。 */
data class ThemePreset(val label: String, val color: Color)

/** 16 个预设种子色（工单 13）。 */
val ThemePresets: List<ThemePreset> = listOf(
    ThemePreset("红", Color(0xFFF44336)),
    ThemePreset("粉", Color(0xFFE91E63)),
    ThemePreset("紫", Color(0xFF9C27B0)),
    ThemePreset("深紫", Color(0xFF673AB7)),
    ThemePreset("靛蓝", BrandPrimary),
    ThemePreset("蓝", Color(0xFF2196F3)),
    ThemePreset("深蓝", Color(0xFF3F51B5)),
    ThemePreset("青", Color(0xFF00BCD4)),
    ThemePreset("蓝绿", Color(0xFF009688)),
    ThemePreset("绿", Color(0xFF4CAF50)),
    ThemePreset("深绿", Color(0xFF2E7D32)),
    ThemePreset("黄绿", Color(0xFFCDDC39)),
    ThemePreset("黄", Color(0xFFFFEB3B)),
    ThemePreset("橙", Color(0xFFFF9800)),
    ThemePreset("棕", Color(0xFF795548)),
    ThemePreset("灰", Color(0xFF9E9E9E)),
)

// ===== 深色模式（可选，保留备用） =====
val DarkBackground = Color(0xFF0F172A)
val DarkSurface = Color(0xFF1E293B)
val DarkOnBackground = Color(0xFFF1F5F9)
