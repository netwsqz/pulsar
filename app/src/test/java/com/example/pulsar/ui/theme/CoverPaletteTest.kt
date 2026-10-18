package com.example.pulsar.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoverPalette 纯逻辑测试：封面色板选色规则 + 浅色/深色模式的渐变柔化。
 * 关键约束：无封面（null / 全中性色板）时必须回退到与现有主题渐变完全一致的颜色，
 * 保证未播放或黑白封面时界面视觉与改动前完全相同。
 */
class CoverPaletteTest {

    // ===== 无封面 / 取色失败：回退主题默认 =====

    @Test
    fun `无封面时浅色模式回退主题默认渐变`() {
        val g = coverGradient(swatches = null, darkTheme = false)
        // 与原 MusicLibraryScreen 背景渐变（primaryContainer → surfaceVariant → background）完全一致
        assertEquals(Color(0xFFE0E7FF), g.top)
        assertEquals(Color(0xFFF1F5F9), g.middle)
        assertEquals(Color(0xFFF8FAFC), g.bottom)
    }

    @Test
    fun `无封面时深色模式回退主题默认渐变`() {
        val g = coverGradient(swatches = null, darkTheme = true)
        // 与深色主题的 primaryContainer → surfaceVariant → background 一致
        assertEquals(Color(0xFF4338CA), g.top)
        assertEquals(Color(0xFF334155), g.middle)
        assertEquals(Color(0xFF0F172A), g.bottom)
    }

    @Test
    fun `无封面但有预设色时按预设色推导兜底渐变`() {
        val preset = Color(0xFF16A34A)
        val g = presetGradient(preset, darkTheme = false)
        // 主色相必须仍是预设色的绿（与默认靛蓝兜底明显不同）。
        val hue = hslOf(g.accent.toArgb())[0]
        assertTrue("hue=$hue", hue in 100f..150f)
        // 与「完全没预设」的默认兜底不同，说明预设确实生效了。
        assertTrue(g.top != defaultCoverGradient(false).top)
    }

    @Test
    fun `灰色预设色也当兜底用（不被饱和度筛选吃掉）`() {
        val g = presetGradient(Color(0xFF808080), darkTheme = false)
        assertTrue(g.top != defaultCoverGradient(false).top)
    }

    @Test
    fun `没有预设色时兜底渐变与默认一致`() {
        assertEquals(defaultCoverGradient(false), presetGradient(null, darkTheme = false))
        assertEquals(defaultCoverGradient(true), presetGradient(null, darkTheme = true))
    }

    @Test
    fun `空色板回退默认渐变`() {
        val g = coverGradient(swatches = emptyList(), darkTheme = false)
        assertEquals(Color(0xFFE0E7FF), g.top)
    }

    // ===== 选色规则 =====

    @Test
    fun `选色取饱和色板中人口最多者`() {
        val swatches = listOf(
            CoverSwatch(0xFFDC2626.toInt(), 100), // 饱和红，人口最高
            CoverSwatch(0xFF2563EB.toInt(), 40)   // 饱和蓝，人口低
        )
        assertEquals(0xFFDC2626.toInt(), selectCoverSwatch(swatches)?.rgb)
    }

    @Test
    fun `人口最高的灰色不饱和时不参选，改选小人口的饱和色`() {
        val swatches = listOf(
            CoverSwatch(0xFFCCCCCC.toInt(), 100), // 灰色 dominant，不饱和
            CoverSwatch(0xFF34D399.toInt(), 10)   // 饱和绿，人口小
        )
        assertEquals(0xFF34D399.toInt(), selectCoverSwatch(swatches)?.rgb)
    }

    @Test
    fun `全中性色板返回 null 主色`() {
        val swatches = listOf(
            CoverSwatch(0xFFCCCCCC.toInt(), 100),
            CoverSwatch(0xFF999999.toInt(), 80)
        )
        assertNull(selectCoverSwatch(swatches))
    }

    @Test
    fun `全中性色板整体回退默认渐变`() {
        val g = coverGradient(
            swatches = listOf(CoverSwatch(0xFFCCCCCC.toInt(), 100)),
            darkTheme = false
        )
        assertEquals(Color(0xFFE0E7FF), g.top)
        assertEquals(Color(0xFFF8FAFC), g.bottom)
    }

    // ===== 浅色模式柔化 =====

    @Test
    fun `红色封面浅色模式柔化为浅粉渐变`() {
        val g = coverGradient(
            swatches = listOf(CoverSwatch(0xFFDC2626.toInt(), 100)),
            darkTheme = false
        )
        val top = g.top.toHsl()
        // 色相保持在红色区间（0° 附近，含环回）
        assertTrue("hue=${top.h}", top.h <= 15f || top.h >= 345f)
        // 顶段明显带封面色：亮度 0.78~0.88（比初版 0.90 更深，专辑色更可辨）
        assertTrue("l=${top.l}", top.l in 0.78f..0.88f)
        // 饱和度带足，让专辑色调一眼可辨
        assertTrue("s=${top.s}", top.s in 0.4f..0.8f)
    }

    @Test
    fun `浅色渐变沿垂直方向越来越浅，底部接近中性`() {
        val g = coverGradient(
            swatches = listOf(CoverSwatch(0xFFDC2626.toInt(), 100)),
            darkTheme = false
        )
        val top = g.top.toHsl()
        val middle = g.middle.toHsl()
        val bottom = g.bottom.toHsl()
        // 亮度递增（顶 → 中 → 底）
        assertTrue(middle.l > top.l)
        assertTrue(bottom.l > middle.l)
        // 饱和度递减
        assertTrue(middle.s < top.s)
        assertTrue(bottom.s < middle.s)
        // 底部接近中性色
        assertTrue("bottomS=${bottom.s}", bottom.s < 0.35f)
    }

    // ===== 深色模式加深 =====

    @Test
    fun `红色封面深色模式加深为暗红渐变`() {
        val g = coverGradient(
            swatches = listOf(CoverSwatch(0xFFDC2626.toInt(), 100)),
            darkTheme = true
        )
        val top = g.top.toHsl()
        val middle = g.middle.toHsl()
        val bottom = g.bottom.toHsl()
        // 顶段是暗红：亮度 0.30~0.48（比初版更深，深色下专辑色更明显）
        assertTrue("l=${top.l}", top.l in 0.30f..0.48f)
        assertTrue("hue=${top.h}", top.h <= 15f || top.h >= 345f)
        // 沿垂直方向越来越暗
        assertTrue(middle.l < top.l)
        assertTrue(bottom.l < middle.l)
        // 底部接近纯黑
        assertTrue("bottomL=${bottom.l}", bottom.l < 0.18f)
    }

    // ===== 色相保留 =====

    @Test
    fun `绿色封面浅色模式仍偏绿（色相被保留）`() {
        val g = coverGradient(
            swatches = listOf(CoverSwatch(0xFF16A34A.toInt(), 100)),
            darkTheme = false
        )
        val hue = g.top.toHsl().h
        assertTrue("hue=$hue", hue in 100f..160f)
    }

    // ===== 强调色（进度条填充）=====

    /** 品牌靛蓝的色相 / 亮度，用来区分「取到了封面主色」和「回退了品牌色」。 */
    private val brandHue = BrandPrimary.toHsl().h

    @Test
    fun `无封面时强调色回退品牌靛蓝`() {
        assertEquals(BrandPrimary, coverAccent(swatches = null))
        assertEquals(BrandPrimary, coverAccent(swatches = emptyList()))
    }

    @Test
    fun `全中性色板回退品牌靛蓝`() {
        assertEquals(BrandPrimary, coverAccent(listOf(CoverSwatch(0xFFCCCCCC.toInt(), 100))))
    }

    @Test
    fun `红色封面的强调色保留红相`() {
        val accent = coverAccent(listOf(CoverSwatch(0xFFDC2626.toInt(), 100))).toHsl()
        assertTrue("hue=${accent.h}", accent.h <= 15f || accent.h >= 345f)
    }

    @Test
    fun `强调色亮度落在深浅两种底子上都读得出的区间`() {
        // 深色沉浸页压在暗封面上要看得见（不能太暗），浅色玻璃条上要有对比（不能太亮）。
        val accent = coverAccent(listOf(CoverSwatch(0xFFDC2626.toInt(), 100))).toHsl()
        assertTrue("l=${accent.l}", accent.l in 0.52f..0.70f)
    }

    @Test
    fun `过饱和的封面主色被压到上限`() {
        val accent = coverAccent(listOf(CoverSwatch(0xFF00FF00.toInt(), 100))).toHsl()
        assertTrue("s=${accent.s}", accent.s <= 0.85f)
    }

    @Test
    fun `低饱和但过选色门槛的封面主色被提到可用饱和度且色相不变`() {
        // 灰蓝（HSL 约 220°/0.16/0.50）：刚过 0.15 的选色门槛，直接用会像一团脏灰。
        val accent = coverAccent(listOf(CoverSwatch(0xFF6B7994.toInt(), 100))).toHsl()
        assertTrue("s=${accent.s}", accent.s >= 0.45f)
        // 色相仍是那个灰蓝（≈220°），而不是回退色品牌靛蓝（≈239°）
        assertTrue("hue=${accent.h}", accent.h in 205f..235f)
        assertTrue("brandHue=$brandHue", kotlin.math.abs(accent.h - brandHue) > 10f)
    }

    @Test
    fun `显式传入的 dominant 优先于人口规则（同一封面只有一个主色）`() {
        // ADR-0005：种子取 vibrant，渐变与强调色必须跟同一个色，否则切页面会跳色。
        val swatches = listOf(
            CoverSwatch(0xFFDC2626.toInt(), 100), // 人口最高的红
            CoverSwatch(0xFF16A34A.toInt(), 5)    // 人口很小的绿（vibrant）
        )
        val vibrant = swatches[1]
        val g = coverGradient(swatches, darkTheme = false, dominant = vibrant)
        val hue = g.top.toHsl().h
        assertTrue("hue=$hue", hue in 100f..160f)
        assertEquals(coverAccent(listOf(vibrant)), g.accent)
    }

    @Test
    fun `渐变结构里带上强调色`() {
        val swatches = listOf(CoverSwatch(0xFFDC2626.toInt(), 100))
        assertEquals(coverAccent(swatches), coverGradient(swatches, darkTheme = true).accent)
        assertEquals(coverAccent(swatches), coverGradient(swatches, darkTheme = false).accent)
    }

    @Test
    fun `无封面时渐变结构里的强调色也回退品牌靛蓝`() {
        assertEquals(BrandPrimary, defaultCoverGradient(darkTheme = false).accent)
        assertEquals(BrandPrimary, defaultCoverGradient(darkTheme = true).accent)
    }
}

/** 测试辅助：Compose Color → HSL 三元组。 */
private data class Hsl(val h: Float, val s: Float, val l: Float)

private fun Color.toHsl(): Hsl {
    val rgb = toArgb()
    val r = (rgb shr 16 and 0xFF) / 255f
    val g = (rgb shr 8 and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return Hsl((h + 360f) % 360f, s, l)
}
