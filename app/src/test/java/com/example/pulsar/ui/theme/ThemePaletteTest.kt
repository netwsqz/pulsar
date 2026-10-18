package com.example.pulsar.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ThemePalette 纯逻辑测试：种子色 → Material3 ColorScheme 的推导规则。
 * 关键约束：色相保留、明度档位结构决定对比度、灰调种子不产生彩色主色、种子色回退链有序。
 */
class ThemePaletteTest {

    private val red = Color(0xFFDC2626)
    private val green = Color(0xFF16A34A)
    private val blue = Color(0xFF2563EB)

    // ===== 色相保留 =====

    @Test
    fun `红绿蓝种子推导出的 primary 保留种子色相`() {
        listOf(red, green, blue).forEach { seed ->
            val expected = seed.toThemeHsl().h
            listOf(false, true).forEach { dark ->
                val primary = deriveColorScheme(seed, dark).primary
                assertHueClose(expected, primary.toThemeHsl().h)
            }
        }
    }

    // ===== 色板完整性 =====

    @Test
    fun `浅色与深色都生成完整色板`() {
        listOf(false, true).forEach { dark ->
            val scheme = deriveColorScheme(red, dark)
            scheme.roles().forEach { (name, color) ->
                assertNotEquals("$name 未指定", Color.Unspecified, color)
                assertEquals("$name 应不透明", 1f, color.alpha)
            }
        }
    }

    // ===== 对比度靠结构档位 =====

    @Test
    fun `onPrimary 与 onSurface 在浅色下取亮档、深色下取暗档`() {
        val light = deriveColorScheme(blue, darkTheme = false)
        assertTrue("light onPrimary l=${light.onPrimary.toThemeHsl().l}", light.onPrimary.toThemeHsl().l > 0.9f)
        assertTrue("light onSurface l=${light.onSurface.toThemeHsl().l}", light.onSurface.toThemeHsl().l < 0.2f)
        assertTrue("light primary l=${light.primary.toThemeHsl().l}", light.primary.toThemeHsl().l < 0.55f)
        assertTrue("light surface l=${light.surface.toThemeHsl().l}", light.surface.toThemeHsl().l > 0.9f)

        val dark = deriveColorScheme(blue, darkTheme = true)
        assertTrue("dark onPrimary l=${dark.onPrimary.toThemeHsl().l}", dark.onPrimary.toThemeHsl().l < 0.25f)
        assertTrue("dark onSurface l=${dark.onSurface.toThemeHsl().l}", dark.onSurface.toThemeHsl().l > 0.85f)
        assertTrue("dark primary l=${dark.primary.toThemeHsl().l}", dark.primary.toThemeHsl().l > 0.6f)
        assertTrue("dark surface l=${dark.surface.toThemeHsl().l}", dark.surface.toThemeHsl().l < 0.2f)
    }

    @Test
    fun `on 角色与底色达到可读对比度（含黄绿色相）`() {
        val yellow = Color(0xFFFACC15)
        listOf(red, green, blue, yellow).forEach { seed ->
            listOf(false, true).forEach { dark ->
                val scheme = deriveColorScheme(seed, dark)
                assertContrast("onPrimary", scheme.primary, scheme.onPrimary, dark)
                assertContrast("onPrimaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer, dark)
                assertContrast("onSurface", scheme.surface, scheme.onSurface, dark)
                assertContrast("onSurfaceVariant", scheme.surfaceVariant, scheme.onSurfaceVariant, dark)
            }
        }
    }

    // ===== 灰调种子 =====

    @Test
    fun `灰调种子不产生彩色 primary`() {
        listOf(Color(0xFF808080), Color(0xFF000000), Color(0xFFFFFFFF)).forEach { grey ->
            listOf(false, true).forEach { dark ->
                val s = deriveColorScheme(grey, dark).primary.toThemeHsl().s
                assertTrue("grey=${grey.toArgb().toUInt().toString(16)} dark=$dark s=$s", s < 1e-4f)
            }
        }
    }

    // ===== surface 系列明度单调 =====

    @Test
    fun `surface 系列明度不乱序`() {
        val light = deriveColorScheme(green, darkTheme = false)
        assertTrue(
            "light surface=${light.surface.toThemeHsl().l} background=${light.background.toThemeHsl().l}",
            light.surface.toThemeHsl().l >= light.background.toThemeHsl().l
        )
        assertTrue(
            "light background=${light.background.toThemeHsl().l} surfaceVariant=${light.surfaceVariant.toThemeHsl().l}",
            light.background.toThemeHsl().l >= light.surfaceVariant.toThemeHsl().l
        )

        val dark = deriveColorScheme(green, darkTheme = true)
        assertTrue(
            "dark surface=${dark.surface.toThemeHsl().l} background=${dark.background.toThemeHsl().l}",
            dark.surface.toThemeHsl().l <= dark.background.toThemeHsl().l
        )
        assertTrue(
            "dark background=${dark.background.toThemeHsl().l} surfaceVariant=${dark.surfaceVariant.toThemeHsl().l}",
            dark.background.toThemeHsl().l <= dark.surfaceVariant.toThemeHsl().l
        )
    }

    // ===== error 不跟种子走 =====

    @Test
    fun `error 在深浅两套里都保持红色系`() {
        listOf(false, true).forEach { dark ->
            val error = deriveColorScheme(green, dark).error.toThemeHsl()
            assertTrue("dark=$dark hue=${error.h}", error.h <= 15f || error.h >= 345f)
        }
    }

    // ===== 种子色回退链 =====

    @Test
    fun `有 vibrant 时优先用 vibrant`() {
        val seed = selectThemeSeed(
            vibrant = CoverSwatch(0xFF16A34A.toInt(), 10),
            swatches = listOf(CoverSwatch(0xFFDC2626.toInt(), 100)),
            preset = blue
        )
        assertEquals(ThemeSeedSource.CoverVibrant, seed.source)
        assertEquals(0xFF16A34A.toInt(), seed.color.toArgb())
    }

    @Test
    fun `vibrant 为空时回退到 selectCoverSwatch 选出的 dominant`() {
        val seed = selectThemeSeed(
            vibrant = null,
            swatches = listOf(
                CoverSwatch(0xFFCCCCCC.toInt(), 100), // 灰，不参选
                CoverSwatch(0xFF2563EB.toInt(), 20)
            ),
            preset = blue
        )
        assertEquals(ThemeSeedSource.CoverDominant, seed.source)
        assertEquals(0xFF2563EB.toInt(), seed.color.toArgb())
    }

    @Test
    fun `封面全灰或没有封面时回退到用户预设色`() {
        listOf(
            null,
            emptyList(),
            listOf(CoverSwatch(0xFFCCCCCC.toInt(), 100))
        ).forEach { swatches ->
            val seed = selectThemeSeed(vibrant = null, swatches = swatches, preset = blue)
            assertEquals(ThemeSeedSource.UserPreset, seed.source)
            assertEquals(blue, seed.color)
        }
    }

    @Test
    fun `全都没有时回退默认品牌色板`() {
        val seed = selectThemeSeed(vibrant = null, swatches = null, preset = null)
        assertEquals(ThemeSeedSource.Default, seed.source)
        assertEquals(BrandPrimary, seed.color)
    }
}

/** 测试覆盖的角色集合（工单要求 UI 实际用到的那些）。 */
private fun ColorScheme.roles(): List<Pair<String, Color>> = listOf(
    "primary" to primary,
    "onPrimary" to onPrimary,
    "primaryContainer" to primaryContainer,
    "onPrimaryContainer" to onPrimaryContainer,
    "secondary" to secondary,
    "onSecondary" to onSecondary,
    "secondaryContainer" to secondaryContainer,
    "onSecondaryContainer" to onSecondaryContainer,
    "tertiary" to tertiary,
    "onTertiary" to onTertiary,
    "tertiaryContainer" to tertiaryContainer,
    "onTertiaryContainer" to onTertiaryContainer,
    "background" to background,
    "onBackground" to onBackground,
    "surface" to surface,
    "onSurface" to onSurface,
    "surfaceVariant" to surfaceVariant,
    "onSurfaceVariant" to onSurfaceVariant,
    "outline" to outline,
    "outlineVariant" to outlineVariant,
    "error" to error,
    "onError" to onError,
)

private data class ThemeHsl(val h: Float, val s: Float, val l: Float)

/** 复用生产端的 HSL 转换（`CoverPalette.kt`），测试不再自造一份。 */
private fun Color.toThemeHsl(): ThemeHsl = hslOf(toArgb()).let { ThemeHsl(it[0], it[1], it[2]) }

private fun assertHueClose(expected: Float, actual: Float, tolerance: Float = 2f) {
    var diff = abs(expected - actual) % 360f
    if (diff > 180f) diff = 360f - diff
    assertTrue("hue expected=$expected actual=$actual diff=$diff", diff <= tolerance)
}

/** WCAG 相对亮度对比度；不引入对比度库。 */
private fun assertContrast(name: String, base: Color, on: Color, dark: Boolean) {
    val la = base.luminance()
    val lb = on.luminance()
    val ratio = (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    assertTrue("$name dark=$dark ratio=$ratio", ratio >= 4.5f)
}
