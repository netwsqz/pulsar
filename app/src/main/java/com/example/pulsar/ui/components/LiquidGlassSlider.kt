package com.example.pulsar.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * 拖动 x 像素 → 进度值：把轨道上的触摸位置线性映射到 [rangeStart, rangeEnd] 并钳制在区间内。
 *
 * 防御：x 或轨道宽度非有限值、轨道宽度 <= 0 时一律返回 rangeStart。轨道宽度为 0 会除零，
 * NaN 会顺着 onValueChange 污染父层的拖动状态（父层拿它去算时间文案 / seek），必须在源头拦住。
 */
internal fun sliderValueForDragX(
    x: Float,
    trackWidthPx: Float,
    rangeStart: Float,
    rangeEnd: Float
): Float {
    if (!x.isFinite() || !trackWidthPx.isFinite() || trackWidthPx <= 0f) return rangeStart
    val fraction = (x / trackWidthPx).coerceIn(0f, 1f)
    return rangeStart + (rangeEnd - rangeStart) * fraction
}

/**
 * 进度值 → 0..1 的填充比例。
 *
 * 区间退化（上界 <= 下界，例如时长未知时传进来的 0f..0f）返回 0，避免除零；
 * value 非有限值同样返回 0，不让脏值画出异常长度的填充。
 */
internal fun sliderFraction(value: Float, rangeStart: Float, rangeEnd: Float): Float {
    val span = rangeEnd - rangeStart
    if (!span.isFinite() || span <= 0f) return 0f
    if (!value.isFinite()) return 0f
    return ((value - rangeStart) / span).coerceIn(0f, 1f)
}

/**
 * 液态玻璃进度条的配色。抽成数据类是为了让同一个控件同时适配两种截然不同的底色：
 * - 全屏播放页是深色沉浸页，用白系（[immersiveSliderColors]）；
 * - 迷你条展开面板落在浅色玻璃条上，用主题色系（[glassBarSliderColors]）。
 *
 * 各色自带 alpha，绘制处不再额外乘系数。
 */
@Immutable
internal data class LiquidGlassSliderColors(
    /** 轨道玻璃表面色：叠在模糊之上，保证轨道在任意底色上都看得见。 */
    val trackSurface: Color,
    /** 已播填充的渐变起点（靠左）。 */
    val fillStart: Color,
    /** 已播填充的渐变终点（前端，更亮）。 */
    val fillEnd: Color,
    /**
     * 填充前端柔光的颜色。**它自己的 alpha 是峰值强度**：绘制处与本色的 alpha 相乘
     * （而不是覆盖），所以传 [Color.Transparent] 就等于彻底关掉柔光。
     */
    val glow: Color
)

/**
 * 深色沉浸页预设（全屏播放页）：玻璃里透出模糊封面，[accent] 是当前专辑主色。
 * 填充从半透明渐到实色、前端柔光同色 —— 读作「专辑色的液体涨上来」。
 *
 * [accent] 与 [trackSurface] 都刻意不给默认值：漏传就会退回一个跟主题无关的固定色，
 * 是这类功能最可能悄悄失效的地方，让它编译不过比让它静默变白强。
 */
@Composable
internal fun immersiveSliderColors(
    accent: Color,
    // 轨道玻璃表面色由调用方按色板给（全屏页恒深底，需要取色板亮端，见 NowPlayingScreen）。
    trackSurface: Color
): LiquidGlassSliderColors = LiquidGlassSliderColors(
    trackSurface = trackSurface,
    fillStart = accent.copy(alpha = 0.6f),
    fillEnd = accent,
    glow = accent
)

/**
 * 浅色玻璃条预设（迷你条展开面板）：轨道表面取自 MaterialTheme（它是叠在玻璃条上的，得跟着主题走），
 * 填充用 [accent]（当前专辑主色）。
 *
 * 全透明 = **关掉前端柔光**。展开面板本身就叠在玻璃条上，再发一团光会糊在一起（真机反馈）。
 * [accent] 同 [immersiveSliderColors] 不给默认值。
 */
@Composable
internal fun glassBarSliderColors(accent: Color): LiquidGlassSliderColors {
    val scheme = MaterialTheme.colorScheme
    return LiquidGlassSliderColors(
        trackSurface = scheme.surface.copy(alpha = 0.35f),
        fillStart = accent.copy(alpha = 0.65f),
        fillEnd = accent,
        glow = Color.Transparent
    )
}

/** 胶囊默认高度：够厚才看得出玻璃的模糊/透镜，又不会喧宾夺主。 */
internal val LiquidGlassSliderHeight = 28.dp

/** 填充前端柔光的基准不透明度（静止时）。 */
private const val HeadGlowIdleAlpha = 0.15f

/** 拖动时前端柔光额外增加的不透明度上限。 */
private const val HeadGlowDragAlpha = 0.35f

/**
 * 液态玻璃胶囊进度条。
 *
 * 视觉两件套（**刻意不做独立拇指**）：
 * 1) **轨道** = [liquidGlass] 采样 [backdrop]（模糊 + 透镜 + 高光），表面再叠一层半透明
 *    [LiquidGlassSliderColors.trackSurface] 保证可读性 —— 玻璃里透出背后的封面 / 列表；
 * 2) **已播** = 从左侧起的胶囊填充 + 横向渐变（前端更亮），前端叠一团柔光 —— 读作「液体涨上来」。
 *
 * 早先做过「填充前端挂一个会横向胀开的玻璃拇指」，真机反馈两种形态（静止的竖条、按下后
 * 鼓起的圆球）都显得突兀：在一条玻璃轨道上多出一个悬空的实体，怎么看都像异物。
 * 现在**取消独立拇指**：播放头就是填充的圆头本身，拖动时只把前端柔光提亮 —— 观感上是
 * 「液体的头部在发光」而不是「多了个东西」，同时仍保留拖动反馈。
 *
 * 交互语义与 Material3 `Slider` 对齐，调用方沿用既有的 `sliderPosition` / `isDragging` 模式：
 * **按下即回调预览值**（`onValueChange`），拖动跟手，抬手才 `onValueChangeFinished`。
 * 手势全程 consume，因此祖先容器（全屏页的下滑收起、面板的纵向滚动）不会在进度条上误触发。
 *
 * **[onValueChangeCancelled] 是必需的**：拖动途中 [valueRange] / `enabled` 变化会让 `pointerInput`
 * 的 key 改变、手势协程被取消（最典型：拖到歌尾时自动切歌，`durationSec` 一变就取消）。
 * 此时抬手回调永远不会来，若不在取消路径上通知调用方，父层的 `isDragging` 会永远卡在 true、
 * 进度显示从此冻结。取消回调**只应清拖动状态、不要 seek**（用户并没有确认这个位置）。
 *
 * @param backdrop 采样源。传 `null` 时降级为不采样背景的纯半透明胶囊（模糊看不到内容时用，零风险）。
 * @param enabled 无媒体时置 false：整体压暗，且不接收手势。
 */
@Composable
internal fun LiquidGlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onValueChangeCancelled: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop?,
    // 无默认值：两个预设都要求显式给出专辑强调色，漏传就编译不过（见 immersiveSliderColors）。
    colors: LiquidGlassSliderColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = LiquidGlassSliderHeight
) {
    // 组件内部的按压态：只用来驱动前端柔光的强弱，父层的 isDragging 仍由 onValueChange 自己维护。
    var dragging by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val fraction = sliderFraction(value, valueRange.start, valueRange.endInclusive)
    // 拖动强度 0..1：按下爬到 1、松手落回 0，低阻尼弹簧让松手那一下带点回弹。
    val dragIntensity by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "liquidGlassDragIntensity"
    )
    // 在这里（组合作用域内）算出柔光透明度：只把结果传进 drawBehind，而不是让 draw 块去读动画状态。
    // 前者每一帧都会让本函数重组、进而重画；后者在 Compose 里是靠不住的（同 SkeletonBox 那个坑）。
    // 与 colors.glow 自带的 alpha **相乘**（不是覆盖）：这样传 Color.Transparent 真的等于关灯，
    // 而 copy(alpha=..) 会把「透明黑」变成半透明黑。
    val headGlowAlpha = colors.glow.alpha * (HeadGlowIdleAlpha + HeadGlowDragAlpha * dragIntensity)
    val capsuleShape = RoundedCornerShape(percent = 50)

    // 轨道：有采样源就做真玻璃，没有就退化成半透明胶囊（视觉稍逊，但绝不会出现「一片空」）。
    val trackModifier = if (backdrop != null) {
        Modifier.liquidGlass(
            backdrop = backdrop,
            surface = colors.trackSurface,
            shape = capsuleShape,
            // 透镜按胶囊高度收窄：面板级的 14/28dp 用在这条 28dp 高的胶囊上会糊成一片。
            // 模糊不跟着收 —— 全 App 统一，玻璃才像同一种材质。
            lensRefraction = height * 0.35f,
            lensDepth = height * 0.7f
        )
    } else {
        Modifier.clip(capsuleShape).background(colors.trackSurface)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(trackModifier)
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                // 本次手势是否还没走到正常结束（抬手回调还没发出去）。
                var pendingFinish = false
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 宽度在**每次手势开始时**取（而不是协程启动时取一次）：旋转 / 分屏后
                        // pointerInput 的 key 没变、协程不会重启，启动时取的那份宽度会是过期的。
                        val width = size.width.toFloat()
                        // 按下就吃掉事件：祖先的下滑收起 / 纵向滚动不会在进度条上启动（拖滑块不该收起页面）。
                        down.consume()
                        dragging = true
                        pendingFinish = true
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        onValueChange(
                            sliderValueForDragX(
                                down.position.x, width, valueRange.start, valueRange.endInclusive
                            )
                        )
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            onValueChange(
                                sliderValueForDragX(
                                    change.position.x, width, valueRange.start, valueRange.endInclusive
                                )
                            )
                        }
                        dragging = false
                        pendingFinish = false
                        onValueChangeFinished()
                    }
                } finally {
                    // 正常结束已在上面清过 pendingFinish；到这里还挂着，说明手势协程是**被取消**的
                    // （key 变化 / 组件被移出组合）——抬手回调永远不会来，必须通知调用方清掉拖动状态，
                    // 否则父层的 isDragging 会永远卡在 true。取消路径**只清状态、不 seek**。
                    dragging = false
                    if (pendingFinish) {
                        pendingFinish = false
                        onValueChangeCancelled()
                    }
                }
            }
    ) {
        // 已播填充：从左侧起的胶囊，前端更亮 + 一团柔光 → 「液体涨上来」。
        // 柔光画在 clip 之前，因此可以溢出到未播轨道上一点点，读作液体头部的光晕。
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .drawBehind {
                        val radius = size.height * 0.8f
                        // 圆心从填充右缘**往左挪一点**：正压在边缘上时，光的右半边会飘在空轨道上，
                        // 像一层浮在外面的光晕；挪进来一些才像填充自身「在发光」。
                        val headX = size.width - radius * 0.4f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(colors.glow.copy(alpha = headGlowAlpha), Color.Transparent),
                                center = Offset(headX, size.height / 2f),
                                radius = radius
                            ),
                            radius = radius,
                            center = Offset(headX, size.height / 2f),
                            blendMode = BlendMode.Plus
                        )
                    }
                    .clip(capsuleShape)
                    .background(
                        Brush.horizontalGradient(
                            0f to colors.fillStart,
                            1f to colors.fillEnd
                        )
                    )
            )
        }
    }
}
