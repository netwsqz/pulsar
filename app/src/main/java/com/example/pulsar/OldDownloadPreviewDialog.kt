package com.example.pulsar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.example.pulsar.download.OldDownloadItem
import com.example.pulsar.download.OldDownloadPreview
import com.example.pulsar.ui.theme.Dimens

/** 同一个只读入口：即使连不上 Jellyfin、回到了登录页，也能核对旧账号的下载。 */
@Composable
internal fun OldDownloadPreviewButton(modifier: Modifier = Modifier) {
    var opened by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { opened = true }, modifier = modifier) { Text("预览旧下载") }
    if (opened) {
        val application = LocalContext.current.applicationContext as PulsarApplication
        OldDownloadPreviewDialog(preview = { application.oldDownloadPreviewer.preview() }, onClose = { opened = false })
    }
}

@Composable
private fun OldDownloadPreviewDialog(preview: suspend () -> OldDownloadPreview, onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<OldDownloadPreview?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = preview()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("预览旧下载") },
        text = {
            when (val report = result) {
                null -> CircularProgressIndicator()
                OldDownloadPreview.NoSavedAccount -> Text("手机上没有可用于核对的旧账号。无法确认下载归属；原有副本不动。")
                OldDownloadPreview.Unavailable -> Text("暂时无法读取本机的下载记录或副本。可以重试，原有副本不动。")
                is OldDownloadPreview.Report -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                ) {
                    item { Text("只核对手机里的下载，不连接 Jellyfin，也不迁移、重下或删除歌曲。") }
                    if (report.complete.isEmpty() && report.unfinished.isEmpty() && report.pending.isEmpty()) {
                        item { Text("没有找到可核对的旧下载。") }
                    }
                    previewGroup("已核对完整", report.complete)
                    previewGroup("未下完", report.unfinished)
                    previewGroup("待核对", report.pending)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            if (result != null) TextButton(onClick = { attempt++ }) { Text("重新核对") }
        },
    )
}

private fun LazyListScope.previewGroup(label: String, entries: List<OldDownloadItem>) {
    item { Text("$label · ${entries.size} 首", style = MaterialTheme.typography.titleSmall) }
    itemsIndexed(entries) { index, entry ->
        val title = entry.title?.takeIf { it.isNotBlank() }
            ?: entry.trackId.takeIf { it.isNotBlank() }?.let { "曲目 ID：$it" }
            ?: "未记录曲目 ID"
        Text(
            "${index + 1}. $title",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.downloadNames.isNotEmpty()) {
            Text(
                entry.downloadNames.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
