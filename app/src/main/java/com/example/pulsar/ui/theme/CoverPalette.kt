package com.example.pulsar.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 从封面色板选出并柔化后的背景渐变色（顶 → 中 → 底，对应垂直渐变的 0 / 0.3 / 1 位置），
 * 外加一条 [accent]：进度条填充用的强调色（见 [coverAccent]），与背景渐变同一趟取色得出。
 */
data class CoverGradient(
    val top: Color,
    val middle: Color,
    val bottom: Color,
    val accent: Color
)

/** 色板样本：主色 RGB + 像素人口数。androidx.palette 的 Swatch 的纯 Kotlin 镜像，便于单元测试。 */
data class CoverSwatch(val rgb: Int, val population: Int)

/** 判定为「有明确色彩」的最低 HSL 饱和度：低于它的灰调样本不参与主色评选。 */
private const val SATURATION_THRESHOLD = 0.15f

/** 强调色的饱和度下限：低于它的封面主色填出来像一团脏灰，提到这个值才读得出颜色。 */
private const val MIN_ACCENT_SATURATION = 0.45f

/** 强调色的饱和度上限：过饱和在液态玻璃上会显得刺眼、压过背景色。 */
private const val MAX_ACCENT_SATURATION = 0.85f

/**
 * 强调色的亮度。取中间值是刻意的：全屏播放页永远是深色沉浸底（要够亮才看得见），
 * 迷你条跟随系统主题、浅色时是白玻璃条（要够深才有对比），一个值让两处都成立，
 * 因此 [coverAccent] 不需要 darkTheme 参数。这个亮度也与品牌靛蓝（l≈0.67）同一档，
 * 换到专辑色后不会突然变淡或变闷。
 */
private const val ACCENT_LIGHTNESS = 0.62f

/** 无封面 / 全中性色板时的回退渐变：与主题 primaryContainer → surfaceVariant → background 完全一致。 */
fun defaultCoverGradient(darkTheme: Boolean): CoverGradient =
    if (darkTheme) {
        CoverGradient(
            top = Color(0xFF4338CA),
            middle = Color(0xFF334155),
            bottom = Color(0xFF0F172A),
            accent = BrandPrimary
        )
    } else {
        CoverGradient(
            top = Color(0xFFE0E7FF),
            middle = Color(0xFFF1F5F9),
            bottom = Color(0xFFF8FAFC),
            accent = BrandPrimary
        )
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
 *
 * [dominant] 允许调用方指定主色（[rememberCoverGradient] 传 vibrant），保证同一张封面
 * 的种子、强调色、渐变是同一个色相；不传时按 [selectCoverSwatch] 的人口规则现选。
 */
fun coverGradient(
    swatches: List<CoverSwatch>?,
    darkTheme: Boolean,
    fallback: CoverGradient = defaultCoverGradient(darkTheme),
    dominant: CoverSwatch? = selectCoverSwatch(swatches.orEmpty())
): CoverGradient {
    val swatch = dominant ?: return fallback
    val hsl = hslOf(swatch.rgb)
    val h = hsl[0]
    val s = hsl[1]
    val accent = accentOf(swatch)
    return if (darkTheme) {
        CoverGradient(
            top = hslColor(h, s * 0.80f, 0.40f),
            middle = hslColor(h, s * 0.50f, 0.26f),
            bottom = hslColor(h, s * 0.25f, 0.12f),
            accent = accent
        )
    } else {
        CoverGradient(
            top = hslColor(h, s * 0.75f, 0.84f),
            middle = hslColor(h, s * 0.45f, 0.92f),
            bottom = hslColor(h, s * 0.20f, 0.97f),
            accent = accent
        )
    }
}

/**
 * 封面色板 → 强调色（进度条填充 + 填充前端的柔光）。
 *
 * 与背景渐变**故意分开**：背景要「柔」（压暗 / 提亮到几乎融进底色），强调色要「跳」——
 * 只保留主色相，亮度固定、饱和度钳进一个区间，保证压在暗封面上看得见、放在浅玻璃条上也有对比。
 *
 * 无封面 / 全中性色板回退品牌靛蓝（[BrandPrimary]），与取色功能引入前迷你条进度条的颜色一致。
 */
fun coverAccent(swatches: List<CoverSwatch>?): Color =
    accentOf(swatches?.takeIf { it.isNotEmpty() }?.let(::selectCoverSwatch))

/** 主色样本 → 强调色；[dominant] 为 null（无封面 / 全灰封面）时回退品牌靛蓝。 */
private fun accentOf(dominant: CoverSwatch?): Color {
    if (dominant == null) return BrandPrimary
    val hsl = hslOf(dominant.rgb)
    val saturation = hsl[1].coerceIn(MIN_ACCENT_SATURATION, MAX_ACCENT_SATURATION)
    return hslColor(hsl[0], saturation, ACCENT_LIGHTNESS)
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
