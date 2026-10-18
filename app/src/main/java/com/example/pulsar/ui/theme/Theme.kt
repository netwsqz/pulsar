package com.example.pulsar.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    seed: Color? = null, // 封面推导种子；null = 旧的固定靛蓝色板
    content: @Composable () -> Unit
) {
    val target = when {
        seed != null -> remember(seed, darkTheme) { deriveColorScheme(seed, darkTheme) }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = animatedColorScheme(target),
        typography = PulsarTypography,
        content = content
    )
}

/** 主题切换的过渡时长：与主界面背景渐变同步（`MainActivity` 也用这个值）。 */
internal const val THEME_TRANSITION_MS = 800

/**
 * 切歌换色时只动画实际用到的角色，800ms 平滑过渡；其余角色（container 系列、
 * inverse 系列等）直接瞬切——界面没有在动画中途观察它们的需求。
 * `animateColorAsState` 自带「从当前值出发」的行为，连续切歌不会跳回起点。
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(THEME_TRANSITION_MS)
    val primary by animateColorAsState(target.primary, spec, label = "themePrimary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "themeOnPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "themePrimaryContainer")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "themeSecondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "themeOnSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "themeTertiary")
    val background by animateColorAsState(target.background, spec, label = "themeBackground")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "themeOnBackground")
    val surface by animateColorAsState(target.surface, spec, label = "themeSurface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "themeOnSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "themeSurfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "themeOnSurfaceVariant")
    val outline by animateColorAsState(target.outline, spec, label = "themeOutline")
    val outlineVariant by animateColorAsState(target.outlineVariant, spec, label = "themeOutlineVariant")
    val error by animateColorAsState(target.error, spec, label = "themeError")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
    )
}
