package com.example.pulsar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.pulsar.ui.theme.Dimens

/**
 * Auxio 风格的区块卡：低饱和表面 + 细描边 + 大圆角。
 *
 * QQ 音乐导入页的凭证/服务/密钥块、设置页的分组、已下载列表的专辑行都用这一层，
 * 专属页和库页因此是同一套「卡片语言」。歌曲/专辑/任务行仍是无卡片的列表语言
 * （图标位 + 主副文本 + 右侧按钮 + 分隔线）。
 *
 * [onClick] 非空时整张卡可点（已下载列表行）；点击涟漪裁在圆角内。
 */
@Composable
internal fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(Dimens.CornerLarge)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                shape
            )
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Dimens.SpaceMidMedium, vertical = Dimens.SpaceMidMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        content = content
    )
}
