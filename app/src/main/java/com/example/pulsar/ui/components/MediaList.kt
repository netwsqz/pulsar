package com.example.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.example.pulsar.ui.theme.Dimens

/**
 * 列表行统一语法（spec §5 / 工单 07）：前槽 → 12dp 间距 → 文字列（weight 1）→ 尾槽（可空）。
 *
 * 前槽默认 48dp（封面 / 序号），多选时调用方把「勾选框 + 封面」一起传进来并把 [leadingWidth]
 * 调到实际宽度。分隔线起点跟着 [leadingWidth] 走——「实际前槽宽 + 12dp」，所以多选时线从
 * 文字左缘开始，不会穿过勾选框；无多选时线从封面右缘开始。骨架也用这个布局，槽位和行高
 * 与真实行完全一致，加载完成不会跳。
 *
 * 手势 / 点击留在调用方：[rowModifier] 是加在整行（不含分隔线）上的，TrackRow 的
 * combinedClickable 与长按锚点测量都挂在这里。
 */
@Composable
internal fun MediaRowLayout(
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    /** 前槽实际宽度；分隔线起点 = 它 + [Dimens.SpaceMidMedium]。多选时是勾选框 + 间距 + 封面。 */
    leadingWidth: Dp = Dimens.TouchTargetSmall,
    leading: @Composable () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            // defaultMinSize 放在 padding 外层：64dp 是含上下 8dp 的整行最小高度，
            // 内容区因此至少 48dp，和封面同一个触摸目标。
            rowModifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimens.MediaRowMinHeight)
                .padding(vertical = Dimens.SpaceSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(leadingWidth), contentAlignment = Alignment.CenterStart) {
                leading()
            }
            Spacer(Modifier.width(Dimens.SpaceMidMedium))
            Column(
                Modifier.weight(1f),
                // 文字列在 48dp 内容区里垂直居中：与封面/勾选框对齐。
                verticalArrangement = Arrangement.Center,
                content = content
            )
            if (trailing != null) {
                // 尾槽固定 48dp：骨架留空占位和真实「更多」按钮占同样的宽，加载完行宽不变。
                Box(Modifier.width(Dimens.TouchTargetSmall), contentAlignment = Alignment.Center) {
                    trailing()
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = leadingWidth + Dimens.SpaceMidMedium),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/**
 * 行内文字列的唯一写法：标题 `bodyLarge` 一行、副标题 `bodySmall` 次要色一行。
 * 当前播放曲目由调用方把 [titleColor] 传成 primary；其余保持默认前景色。
 */
@Composable
internal fun MediaRowText(
    title: String,
    subtitle: String?,
    titleColor: Color = Color.Unspecified
) {
    Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = titleColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
