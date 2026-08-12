package com.example.pulsar.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Pulsar 液态玻璃主题
 * 默认使用浅色模式，配合玻璃态效果营造轻盈现代的视觉体验
 */

private val LightColorScheme = lightColorScheme(
    // 品牌色
    primary = BrandPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF), // 极浅靛蓝
    onPrimaryContainer = Color(0xFF1E1B4B),

    // 次要色
    secondary = BrandSecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E8FF), // 极浅紫色
    onSecondaryContainer = Color(0xFF4C1D95),

    // 强调色（播放绿）
    tertiary = BrandAccent,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5), // 极浅翠绿
    onTertiaryContainer = Color(0xFF065F46),

    // 背景与表面
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,

    // 边框
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,

    // 功能色
    error = ErrorRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF4338CA),
    onPrimaryContainer = Color(0xFFE0E7FF),

    secondary = BrandSecondary,
    onSecondary = Color(0xFF3B0764),
    secondaryContainer = Color(0xFF6B21A8),
    onSecondaryContainer = Color(0xFFF3E8FF),

    tertiary = BrandAccent,
    onTertiary = Color(0xFF065F46),
    tertiaryContainer = Color(0xFF047857),
    onTertiaryContainer = Color(0xFFD1FAE5),

    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = Color(0xFF94A3B8),

    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),

    error = ErrorRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

@Composable
fun PulsarTheme(
    darkTheme: Boolean = false, // 默认浅色模式
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulsarTypography,
        content = content
    )
}
