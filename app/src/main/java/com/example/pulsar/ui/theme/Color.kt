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
val SuccessGreen = Color(0xFF10B981)
val WarningAmber = Color(0xFFF59E0B)
val ErrorRed = Color(0xFFEF4444)

// ===== 玻璃效果专用色（半透明白色） =====
val GlassWhite = Color(0xCCFFFFFF) // 80% 不透明度的白色，用于玻璃态卡片
val GlassBorder = Color(0x33FFFFFF) // 20% 不透明度的白色，用于玻璃边框

// ===== 深色模式（可选，保留备用） =====
val DarkBackground = Color(0xFF0F172A)
val DarkSurface = Color(0xFF1E293B)
val DarkOnBackground = Color(0xFFF1F5F9)
