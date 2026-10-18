package com.example.pulsar.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/**
 * 主题种子色的来源。优先级见 [selectThemeSeed] / ADR-0005：
 * 封面鲜艳色 → 封面主色 → 用户预设色 → 品牌靛蓝。
 */
enum class ThemeSeedSource { CoverVibrant, CoverDominant, UserPreset, Default }

/** 一次主题推导的输入：种子色 + 它是从哪来的（设置页显示「当前生效」）。 */
data class ThemeSeed(val color: Color, val source: ThemeSeedSource)

/** 低于这个饱和度的种子视为灰调：推导出的强调色保持无彩，不硬塞一个色相。 */
private const val GREY_SATURATION = 0.02f

/**
 * 种子色选取与回退链。`vibrant` 是 androidx.palette 的 Vibrant swatch（调用方负责取），
 * `swatches` 是完整色板（回退到 [selectCoverSwatch] 的主色规则），`preset` 是用户自选预设色。
 */
fun selectThemeSeed(
    vibrant: CoverSwatch?,
    swatches: List<CoverSwatch>?,
    preset: Color?
): ThemeSeed = when {
    vibrant != null -> ThemeSeed(Color(vibrant.rgb), ThemeSeedSource.CoverVibrant)
    else -> selectCoverSwatch(swatches.orEmpty())?.let {
        ThemeSeed(Color(it.rgb), ThemeSeedSource.CoverDominant)
    } ?: preset?.let { ThemeSeed(it, ThemeSeedSource.UserPreset) }
    ?: ThemeSeed(BrandPrimary, ThemeSeedSource.Default)
}

/**
 * 种子色 → 完整 Material3 色板（纯函数，无 Android 运行时依赖）。
 *
 * 对比度**靠结构保证**：每对 `on*` 角色都由底色按固定规则取「最亮档或最暗档」中对比更高的一档
 * （见 [onColor]），不做逐对事后校验。强调色的饱和度从种子色继承并钳进可用区间，灰调种子保持无彩。
 * 色相保留：primary 与种子同色相，secondary 同色相低饱和，tertiary 固定旋转 60°。
 *
 * 手写而非引入依赖的原因见 ADR-0005。
 */
fun deriveColorScheme(seed: Color, darkTheme: Boolean): ColorScheme {
    val hsl = hslOf(seed.toArgb())
    val h = hsl[0]
    val s = hsl[1]
    val primarySat = accentSat(s, 1f)
    val secondarySat = accentSat(s, 0.55f)
    val tertiarySat = accentSat(s, 0.85f)
    val tertiaryHue = h + 60f
    // 中性面只保留一点种子色相，才是「带封面色调的中性 surface」而不是又一层彩色渐变。
    val neutralSat = { factor: Float -> if (s < GREY_SATURATION) 0f else s * factor }

    return if (darkTheme) {
        val primary = hslColor(h, primarySat, 0.75f)
        val primaryContainer = hslColor(h, primarySat, 0.24f)
        val secondary = hslColor(h, secondarySat, 0.72f)
        val secondaryContainer = hslColor(h, secondarySat, 0.24f)
        val tertiary = hslColor(tertiaryHue, tertiarySat, 0.72f)
        val tertiaryContainer = hslColor(tertiaryHue, tertiarySat, 0.24f)
        val background = hslColor(h, neutralSat(0.25f), 0.09f)
        val surface = hslColor(h, neutralSat(0.20f), 0.07f)
        val surfaceVariant = hslColor(h, neutralSat(0.25f), 0.16f)
        val error = hslColor(0f, 0.72f, 0.70f)

        darkColorScheme(
            primary = primary,
            onPrimary = onColor(h, primarySat, primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColor(h, primarySat, primaryContainer),

            secondary = secondary,
            onSecondary = onColor(h, secondarySat, secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColor(h, secondarySat, secondaryContainer),

            tertiary = tertiary,
            onTertiary = onColor(tertiaryHue, tertiarySat, tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColor(tertiaryHue, tertiarySat, tertiaryContainer),

            background = background,
            onBackground = onColor(h, neutralSat(0.20f), background),
            surface = surface,
            onSurface = onColor(h, neutralSat(0.20f), surface),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onColor(h, neutralSat(0.20f), surfaceVariant),

            // M3 组件内部会用到这些角色（弹窗/菜单/Snackbar/开关），不推导就会落回默认紫色。
            surfaceContainerLowest = hslColor(h, neutralSat(0.20f), 0.05f),
            surfaceContainerLow = hslColor(h, neutralSat(0.20f), 0.10f),
            surfaceContainer = hslColor(h, neutralSat(0.20f), 0.12f),
            surfaceContainerHigh = hslColor(h, neutralSat(0.20f), 0.14f),
            surfaceContainerHighest = hslColor(h, neutralSat(0.20f), 0.17f),
            inverseSurface = hslColor(h, neutralSat(0.20f), 0.92f),
            inverseOnSurface = hslColor(h, neutralSat(0.20f), 0.12f),
            inversePrimary = hslColor(h, primarySat, 0.45f),

            outline = hslColor(h, neutralSat(0.20f), 0.52f),
            outlineVariant = hslColor(h, neutralSat(0.20f), 0.28f),

            // 错误色永远红系，不跟种子色走——错误提示变绿会误导用户。
            error = error,
            onError = onColor(0f, 0.72f, error),
        )
    } else {
        val primary = hslColor(h, primarySat, 0.45f)
        val primaryContainer = hslColor(h, primarySat, 0.90f)
        val secondary = hslColor(h, secondarySat, 0.42f)
        val secondaryContainer = hslColor(h, secondarySat, 0.91f)
        val tertiary = hslColor(tertiaryHue, tertiarySat, 0.42f)
        val tertiaryContainer = hslColor(tertiaryHue, tertiarySat, 0.90f)
        val background = hslColor(h, neutralSat(0.25f), 0.975f)
        val surface = hslColor(h, neutralSat(0.20f), 0.99f)
        val surfaceVariant = hslColor(h, neutralSat(0.25f), 0.93f)
        val error = hslColor(0f, 0.72f, 0.48f)

        lightColorScheme(
            primary = primary,
            onPrimary = onColor(h, primarySat, primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = onColor(h, primarySat, primaryContainer),

            secondary = secondary,
            onSecondary = onColor(h, secondarySat, secondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onColor(h, secondarySat, secondaryContainer),

            tertiary = tertiary,
            onTertiary = onColor(tertiaryHue, tertiarySat, tertiary),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onColor(tertiaryHue, tertiarySat, tertiaryContainer),

            background = background,
            onBackground = onColor(h, neutralSat(0.20f), background),
            surface = surface,
            onSurface = onColor(h, neutralSat(0.20f), surface),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onColor(h, neutralSat(0.20f), surfaceVariant),

            // M3 组件内部会用到这些角色（弹窗/菜单/Snackbar/开关），不推导就会落回默认紫色。
            surfaceContainerLowest = hslColor(h, neutralSat(0.20f), 1.00f),
            surfaceContainerLow = hslColor(h, neutralSat(0.20f), 0.97f),
            surfaceContainer = hslColor(h, neutralSat(0.20f), 0.95f),
            surfaceContainerHigh = hslColor(h, neutralSat(0.20f), 0.92f),
            surfaceContainerHighest = hslColor(h, neutralSat(0.20f), 0.90f),
            inverseSurface = hslColor(h, neutralSat(0.20f), 0.16f),
            inverseOnSurface = hslColor(h, neutralSat(0.20f), 0.96f),
            inversePrimary = hslColor(h, primarySat, 0.75f),

            outline = hslColor(h, neutralSat(0.20f), 0.60f),
            outlineVariant = hslColor(h, neutralSat(0.20f), 0.80f),

            error = error,
            onError = onColor(0f, 0.72f, error),
        )
    }
}

/**
 * `on*` 角色：在「最暗档 / 最亮档」两个固定候选里按相对亮度选对比更高的那个。
 * 这是**一条结构规则**，不是逐对配色校验；但必须看相对亮度而不是 HSL 明度——
 * 黄色 l=0.45 已经很亮，按明度档位给白字会糊成一片。
 */
private fun onColor(hue: Float, saturation: Float, base: Color): Color {
    // 候选不止纯黑纯白：容器色本身已经很暗/很亮时，纯黑纯白会偏刺眼，所以用近黑/近白两档。
    // 若两档都不够（中等亮度的底色），就往更极端的方向再走一步。
    val dark = hslColor(hue, saturation, 0.06f)
    val light = hslColor(hue, saturation, 0.98f)
    return if (contrastRatio(base, dark) >= contrastRatio(base, light)) dark else light
}

/** WCAG 相对亮度对比度（1..21）。只用于上面二选一，不做逐对调整。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** 强调色饱和度：灰调种子保持 0；有彩种子继承原饱和度并钳进可用区间。 */
private fun accentSat(seedSaturation: Float, factor: Float): Float =
    if (seedSaturation < GREY_SATURATION) 0f
    else (seedSaturation * factor).coerceIn(0.30f, 0.80f)
