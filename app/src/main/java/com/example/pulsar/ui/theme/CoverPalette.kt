package com.example.pulsar.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** 从封面色板选出并柔化后的三段背景渐变色（顶 → 中 → 底，对应垂直渐变的 0 / 0.3 / 1 位置）。 */
data class CoverGradient(val top: Color, val middle: Color, val bottom: Color)

/** 色板样本：主色 RGB + 像素人口数。androidx.palette 的 Swatch 的纯 Kotlin 镜像，便于单元测试。 */
data class CoverSwatch(val rgb: Int, val population: Int)

/** 判定为「有明确色彩」的最低 HSL 饱和度：低于它的灰调样本不参与主色评选。 */
private const val SATURATION_THRESHOLD = 0.15f

/** 无封面 / 全中性色板时的回退渐变：与主题 primaryContainer → surfaceVariant → background 完全一致。 */
fun defaultCoverGradient(darkTheme: Boolean): CoverGradient =
    if (darkTheme) {
        CoverGradient(top = Color(0xFF4338CA), middle = Color(0xFF334155), bottom = Color(0xFF0F172A))
    } else {
        CoverGradient(top = Color(0xFFE0E7FF), middle = Color(0xFFF1F5F9), bottom = Color(0xFFF8FAFC))
    }

/**
 * 从封面色板中选主色：只在「饱和」（有明确色彩）的样本里挑人口最多的；
 * 全是灰调（黑白封面）返回 null，由调用方回退默认渐变。
 */
fun selectCoverSwatch(swatches: List<CoverSwatch>): CoverSwatch? =
    swatches.filter { hslOf(it.rgb)[1] >= SATURATION_THRESHOLD }.maxByOrNull { it.population }

/**
 * 封面色板 → 背景渐变：保留封面主色的色相，把亮度/饱和度压进液态玻璃风格的柔和区间——
 * 浅色模式整体提亮（顶段浅 tint，向下渐白），深色模式整体压暗（顶段暗色，向下渐黑）。
 */
fun coverGradient(
    swatches: List<CoverSwatch>?,
    darkTheme: Boolean,
    fallback: CoverGradient = defaultCoverGradient(darkTheme)
): CoverGradient {
    val dominant = swatches?.takeIf { it.isNotEmpty() }?.let(::selectCoverSwatch)
        ?: return fallback
    val hsl = hslOf(dominant.rgb)
    val h = hsl[0]
    val s = hsl[1]
    return if (darkTheme) {
        CoverGradient(
            top = hslColor(h, s * 0.80f, 0.40f),
            middle = hslColor(h, s * 0.50f, 0.26f),
            bottom = hslColor(h, s * 0.25f, 0.12f)
        )
    } else {
        CoverGradient(
            top = hslColor(h, s * 0.75f, 0.84f),
            middle = hslColor(h, s * 0.45f, 0.92f),
            bottom = hslColor(h, s * 0.20f, 0.97f)
        )
    }
}

/** RGB(Int) → HSL，返回 [h(0..360), s(0..1), l(0..1)]。 */
internal fun hslOf(rgb: Int): FloatArray {
    val r = (rgb shr 16 and 0xFF) / 255f
    val g = (rgb shr 8 and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return floatArrayOf((h + 360f) % 360f, s, l)
}

/** HSL → Compose Color（不透明）。 */
internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = ((h % 360f + 360f) % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}
