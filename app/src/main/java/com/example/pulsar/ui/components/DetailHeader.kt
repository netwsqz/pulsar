package com.example.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.example.pulsar.AlbumCover
import com.example.pulsar.data.jellyfin.JellyfinRequest
import com.example.pulsar.ui.theme.Dimens

/** 详情头的整组操作；没有整页播放语义时不创建它，不再用布尔开关加占位参数。 */
internal data class DetailActions(
    val playLabel: String,
    val playable: Boolean,
    val onPlay: () -> Unit,
    val onShuffle: () -> Unit,
    val download: (@Composable () -> Unit)? = null
)

/**
 * 详情页共用的真实头部（#26）：大封面 + 居中标题 + 副标题 + 摘要 + 离线提示。
 *
 * [actions] 非空是带播放 / 随机 / 可选下载的变体（专辑、播放列表），null 是纯信息变体
 * （艺术家组）。列表与网格共用同一份头部，始终随内容滚动，不参与曲目区的骨架 / 错误 / 空态。
 */
@Composable
internal fun DetailHeader(
    cover: JellyfinRequest?,
    coverDescription: String?,
    title: String,
    subtitle: String,
    summary: String,
    offlineNotice: String?,
    actions: DetailActions? = null
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumCover(
            cover,
            coverDescription,
            Modifier.fillMaxWidth(0.62f).aspectRatio(1f),
            cornerRadius = Dimens.CornerLarge
        )
        Spacer(Modifier.height(Dimens.SpaceLarge))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            // 长名字最多两行：头部在内容流里会随页面滚走，不再把下方内容挤没。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dimens.SpaceTiny))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Dimens.SpaceTiny))
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        actions?.let { detailActions ->
            Spacer(Modifier.height(Dimens.SpaceMedium))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMidMedium)) {
                Button(onClick = detailActions.onPlay, enabled = detailActions.playable) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.SpaceMidLarge)
                    )
                    Spacer(Modifier.width(Dimens.SpaceSmall))
                    Text(detailActions.playLabel)
                }
                FilledTonalButton(onClick = detailActions.onShuffle, enabled = detailActions.playable) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.SpaceMidLarge)
                    )
                    Spacer(Modifier.width(Dimens.SpaceSmall))
                    Text("随机播放")
                }
            }
            // 下载状态仍由 DownloadActionButton 自己算，离线隐藏规则留在各页面。
            detailActions.download?.let { download ->
                Spacer(Modifier.height(Dimens.SpaceMidMedium))
                download()
            }
        }
        offlineNotice?.let { notice ->
            Spacer(Modifier.height(Dimens.SpaceMidMedium))
            OfflineNoticeText(notice)
        }
        Spacer(Modifier.height(Dimens.SpaceLarge))
    }
}
