package com.example.pulsar.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/** 浮窗尺寸。宽度写死：百分比数字位数在 5 与 100 之间跳，跟随内容会让胶囊一缩一胀。 */
private val VolumeFeedbackWidth = 168.dp
private val VolumeFeedbackHeight = 44.dp

/**
 * 数字栏的最小宽度，数字**左对齐**紧贴胶囊左内边。
 *
 * 早先这里是固定 34dp + 右对齐，两位数时左半边空出一大截，读起来像排版塌了。
 * 用 `widthIn(min =)` 而不是固定宽度：系统字体放大后三位数会撑破固定宽度被截成省略号。
 * 最小宽度按三位数的字宽给，"5" 与 "100" 切换时右侧填充条的左缘也就纹丝不动。
 */
private val VolumeFeedbackNumberWidth = 28.dp

/**
 * 音量反馈浮窗：独占模式下音量键被 App 截获，系统自己的音量条不会弹（见
 * docs/usb-volume-key-interception.md §11.3），这里补上唯一的视觉反馈。
 *
 * 位置由调用方给（顶部玻璃岛正下方），本组件只管「长得像什么 + 什么时候在」。
 *
 * **不吃触摸**：整棵子树不带任何手势修饰符，可见的那 1.5 秒里手指仍能点穿它底下的玻璃岛。
 *
 * @param state 显示窗口，来自 [VolumeFeedback]；[VolumeFeedbackState.percent] 在淡出期间仍有效。
 * @param backdrop 采样源。传 `null` 时降级为不采样背景的纯半透明胶囊（零风险）。
 * @param accent 当前专辑主色，用于填充条。
 */
@Composable
internal fun VolumeFeedbackOverlay(
    state: VolumeFeedbackState,
    backdrop: Backdrop?,
    accent: Color,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        // 进出场比保持时间短得多，读起来是「随按随到、自己慢慢淡掉」，不是一个需要等待的动画。
        enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.88f),
        exit = fadeOut(tween(280)) + scaleOut(tween(280), targetScale = 0.94f)
    ) {
        VolumeFeedbackCapsule(percent = state.percent, backdrop = backdrop, accent = accent)
    }
}

/** 横向胶囊本体：左数字、右填充条，材质与顶部玻璃岛同款（模糊 + 透镜 + 高光）。
 * 前景色由 [GlassPanel] 统一给 onSurface：深色主题下数字不再是默认黑。 */
@Composable
private fun VolumeFeedbackCapsule(percent: Int, backdrop: Backdrop?, accent: Color) {
    // 可读性表面色：非组合的 onDrawSurface 里读不到 MaterialTheme，先取出来（同 GlassChipBar）。
    val scheme = MaterialTheme.colorScheme
    val glassSurface = scheme.surface
    val capsuleShape = RoundedCornerShape(percent = 50)
    // 复用进度条的换算（已单测）：0..100 映射到 0..1，脏值一律给 0。
    val fraction = sliderFraction(percent.toFloat(), 0f, 100f)

    // 与顶部玻璃岛同源（liquidGlass 默认值）：浮窗就浮在它正下方，两者材质必须连成一套。
    // backdrop 为 null 时 GlassPanel 退化成同色平板，绝不会出现「一片空」。
    GlassPanel(
        backdrop = backdrop,
        surface = glassSurface.copy(alpha = 0.8f),
        modifier = Modifier
            .width(VolumeFeedbackWidth)
            .height(VolumeFeedbackHeight),
        shape = capsuleShape
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$percent",
                modifier = Modifier.widthIn(min = VolumeFeedbackNumberWidth),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.width(10.dp))
            // 空槽用 onSurface 的低透明度而不是 surface 同色系：叠在 80% 的玻璃表面上时，
            // 同色系几乎看不见槽的边界，深浅主题还会各偏一次。
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(capsuleShape)
                    .background(scheme.onSurface.copy(alpha = 0.18f))
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(capsuleShape)
                            .background(
                                Brush.horizontalGradient(
                                    0f to accent.copy(alpha = 0.65f),
                                    1f to accent
                                )
                            )
                    )
                }
            }
        }
    }
}
