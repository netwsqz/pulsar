package com.example.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.example.pulsar.ui.theme.Dimens

/**
 * 加载失败态：图标 + 错误文案 + 可选「重新加载」。
 *
 * 撑满宽度 + 居中：空态/错误态在 LazyColumn item 里也要从一侧到另一侧居中，
 * 不能只在自身固有宽度里居中（spec §5）。调用方不用再各自传 fillMaxWidth。
 *
 * 从 MainActivity 移到 components（#24）：[LibraryScreenScaffold] 按 [LoadState] 统一渲染三态，
 * 页面不再各自摆一份错误块。
 */
@Composable
internal fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.SpaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(Dimens.TouchTargetSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        onRetry?.let { retry ->
            TextButton(onClick = retry) { Text("重新加载") }
        }
    }
}

/**
 * 空态：图标 + 文案。与 [ErrorState] 同一套居中语法，只是文案用次要色、不带重试。
 *
 * 从 MainActivity 移到 components（#24），理由同 [ErrorState]。
 */
@Composable
internal fun EmptyState(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Dimens.SpaceMedium),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(Dimens.TouchTargetSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 离线过滤生效时标题下的一行小字（ADR-0008）：解释歌为什么变少了。
 * 字号跟行内歌手副标题一致（bodySmall）——离线提示比副标题还小就看不清（spec §11）。 */
@Composable
internal fun OfflineNoticeText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
