package com.example.pulsar.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 液态玻璃材质的唯一出处：模糊 + 透镜 + 高光。
 *
 * 全 App 的玻璃面都走 [liquidGlass]，每个面原先各写各的数字（模糊 8 / 10 / 16dp 三种，透镜四套），
 * 观感上就是「同一套材质到处不一样」。
 *
 * **模糊取 8dp 是看真机定的**：曾经统一到 16dp（面板原来的档位），真机上偏糊——玻璃下的内容糊成一片，
 * 反而不像玻璃。降到 8dp 后六个面一起变清，面板那三个（岛 / 条 / 浮窗）降得最多。
 *
 * 两处**允许且只允许**偏离默认值的地方：
 * - **透镜折射按控件尺寸推**（28dp 的进度胶囊、44dp 圆钮用面板级的 14/28dp 会被整个拧歪）；
 * - **表面色（含 alpha）**，它取决于玻璃底下压的是什么，是各自单独调过的可读性取舍。
 * 调用方传显式值时必须在注释里写明为什么。
 */

/** 模糊半径：全 App 统一。改这一个数就是全 App 玻璃的清晰度。 */
internal val LiquidGlassBlurRadius = 8.dp

/** 面板级透镜折射高度（顶部玻璃岛 / 底部播放条 / 音量浮窗共用）。 */
internal val LiquidGlassLensRefraction = 14.dp

/** 面板级透镜深度，与 [LiquidGlassLensRefraction] 成对使用。 */
internal val LiquidGlassLensDepth = 28.dp

/** 悬浮面板统一圆角（顶部玻璃岛 / 底部播放条）。 */
internal val LiquidGlassPanelShape = RoundedCornerShape(20.dp)

/**
 * 液态玻璃：采样 [backdrop]，叠上模糊、透镜折射与库自带的边缘高光 / 投影，最后铺一层
 * [surface] 半透明表面色保证可读性。
 *
 * [surface] 的 alpha **不在这里统一**：它取决于玻璃底下压的是什么（迷你条压在滚动的曲库上、
 * 浮窗压在自己的玻璃岛上），是各自单独调过的可读性取舍，跟着模糊一起改会翻车。
 *
 * @param blurRadius 模糊半径，默认全 App 统一值。
 * @param lensRefraction 透镜折射高度（越大玻璃越"鼓"）。小控件要按自身尺寸收窄，见文件头。
 * @param lensDepth 折射影响深度。
 */
internal fun Modifier.liquidGlass(
    backdrop: Backdrop,
    surface: Color,
    shape: Shape = LiquidGlassPanelShape,
    blurRadius: Dp = LiquidGlassBlurRadius,
    lensRefraction: Dp = LiquidGlassLensRefraction,
    lensDepth: Dp = LiquidGlassLensDepth
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(blurRadius.toPx())
        lens(lensRefraction.toPx(), lensDepth.toPx())
    },
    onDrawSurface = { drawRect(surface) }
)
