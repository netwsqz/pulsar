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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulsar.domain.model.Track
import com.example.pulsar.download.ExistingDownloadApply
import com.example.pulsar.download.ExistingDownloadView
import com.example.pulsar.download.OldDownloadItem
import com.example.pulsar.download.OldDownloadPreview
import com.example.pulsar.playback.PlaybackUiState
import com.example.pulsar.ui.theme.Dimens
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@Composable
internal fun OldDownloadPreviewButton(onApplied: (() -> Unit)? = null) {
    val application = LocalContext.current.applicationContext as PulsarApplication
    val account by rememberOldDownloadAccount(application)
    var opened by rememberSaveable(account) { mutableStateOf(false) }
    TextButton(onClick = { opened = true }) { Text("预览旧下载") }
    if (opened) OldDownloadPreviewDialog(application, onApplied, onClose = { opened = false })
}

@Composable
private fun OldDownloadPreviewDialog(
    application: PulsarApplication,
    onApplied: (() -> Unit)?,
    onClose: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<OldDownloadPreview?>(null) }
    var applying by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) {
        result = null
        result = application.oldDownloadPreviewer.preview()
    }
    val report = result as? OldDownloadPreview.Report
    AlertDialog(
        onDismissRequest = { if (!applying) onClose() },
        title = { Text("预览旧下载") },
        text = {
            when (val preview = result) {
                null -> CircularProgressIndicator()
                OldDownloadPreview.NoSavedAccount -> Text("手机上没有可用于核对的旧账号。无法确认下载归属；原有副本不动。")
                OldDownloadPreview.Unavailable -> Text("暂时无法读取本机的下载记录或副本。可以重试，原有副本不动。")
                is OldDownloadPreview.Report -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                ) {
                    item { Text("只核对手机里的下载，不连接 Jellyfin，也不重下、移动或删除歌曲。") }
                    if (onApplied != null && preview.complete.isNotEmpty()) {
                        item { Text("确认后，已核对完整的曲目会加入「已有下载」，只读手机里的原副本。不会导入旧播放列表，也不会切换默认曲库。") }
                    }
                    message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (preview.complete.isEmpty() && preview.unfinished.isEmpty() && preview.pending.isEmpty()) {
                        item { Text("没有找到可核对的旧下载。") }
                    }
                    previewGroup("已核对完整", preview.complete)
                    previewGroup("未下完", preview.unfinished)
                    previewGroup("待核对", preview.pending)
                }
            }
        },
        confirmButton = {
            if (onApplied != null && report != null && report.complete.isNotEmpty()) {
                TextButton(enabled = !applying, onClick = {
                    applying = true
                    message = null
                    scope.launch {
                        when (application.downloadLibrary.confirmExistingDownloads(report)) {
                            ExistingDownloadApply.Applied, ExistingDownloadApply.Partial -> {
                                onApplied()
                                onClose()
                            }
                            ExistingDownloadApply.RecheckRequired -> {
                                onApplied()
                                message = "确认记录已保存，但暂时无法核对所有副本。请重新核对；原副本保留。"
                            }
                            ExistingDownloadApply.PreviewChanged -> message = "下载归属或核对结果已变，请重新核对后再确认。"
                            ExistingDownloadApply.Unavailable -> message = "未能保存，旧副本和上次确认的结果都保留。请检查手机空间后重试。"
                        }
                        applying = false
                    }
                }) { Text(if (applying) "正在保存…" else "确认加入已有下载") }
            } else {
                TextButton(onClick = onClose) { Text("关闭") }
            }
        },
        dismissButton = {
            if (result != null) TextButton(enabled = !applying, onClick = { message = null; attempt++ }) { Text("重新核对") }
            if (onApplied != null && report?.complete?.isNotEmpty() == true) {
                TextButton(enabled = !applying, onClick = onClose) { Text("取消") }
            }
        },
    )
}

@Composable
private fun rememberOldDownloadAccount(application: PulsarApplication) =
    remember(application) { application.sessionManager.savedDownloadAccountChanges.catch { emit(null) } }
        .collectAsStateWithLifecycle(initialValue = null)

@Composable
internal fun rememberExistingDownloads(
    application: PulsarApplication,
    cachedAlbums: List<CachedAlbum>?,
): Pair<ExistingDownloadView?, () -> Unit> {
    val account by rememberOldDownloadAccount(application)
    val library = application.downloadLibrary
    val failures by checkNotNull(library.existingPlaybackFailures).collectAsStateWithLifecycle()
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(account) { mutableStateOf<ExistingDownloadView?>(null) }
    LaunchedEffect(account, attempt, failures, cachedAlbums) {
        result = library.loadExistingDownloads()
    }
    return result to {
        library.resetExistingPlaybackFailures()
        attempt++
    }
}

internal fun LazyListScope.existingDownloadGroup(
    view: ExistingDownloadView?,
    playback: PlaybackUiState,
    onPlay: (List<Track>, String) -> Unit,
    onReload: () -> Unit,
) {
    item(key = "existing-header") {
        Text("已有下载", style = MaterialTheme.typography.titleMedium)
        Text("直接播放手机上的原副本，不需要电脑或 Jellyfin 在线。", style = MaterialTheme.typography.bodySmall)
        OldDownloadPreviewButton(onApplied = onReload)
    }
    when (view) {
        null -> item { CircularProgressIndicator() }
        ExistingDownloadView.NoSavedAccount -> item { Text("没有保存的旧账号，无法确认归属；原副本保留。") }
        ExistingDownloadView.NeedsConfirmation -> item { Text("还没有确认的副本。可先预览旧下载，再确认加入。") }
        ExistingDownloadView.Unavailable -> item {
            Text("暂时读不到已有下载，原副本和已确认的结果保留。")
            TextButton(onClick = onReload) { Text("重新加载") }
        }
        is ExistingDownloadView.Ready -> {
            val playlist = view.playable.map { it.toTrack() }
            item { Text("可离线播放 · ${playlist.size} 首", style = MaterialTheme.typography.titleSmall) }
            if (view.needsConfirmation) item { Text("还有完整副本尚未确认加入，可重新预览。") }
            if (!playback.isConnected) item { Text(playback.status) }
            val playingId = playback.currentMediaItem?.mediaId
            if (playlist.any { it.id == playingId }) {
                playback.playbackError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            }
            trackList(
                tracks = playlist,
                currentTrackId = playingId,
                isPlaying = playback.isPlaying,
                coverFor = { null },
                onPlayTrack = onPlay,
                enabled = playback.isConnected,
                allowMenu = false,
            )
            if (view.unavailable.isNotEmpty()) {
                previewGroup("原副本暂不可播（保留记录）", view.unavailable)
                item { TextButton(onClick = onReload) { Text("重新检查") } }
            }
            if (view.unfinished.isNotEmpty()) previewGroup("未下完", view.unfinished)
            if (view.pending.isNotEmpty()) previewGroup("待核对（未应用）", view.pending)
        }
    }
}

private fun LazyListScope.previewGroup(label: String, entries: List<OldDownloadItem>) {
    item { Text("$label · ${entries.size} 首", style = MaterialTheme.typography.titleSmall) }
    itemsIndexed(entries) { index, entry ->
        Text(
            "${index + 1}. ${entry.displayTitle()}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        downloadNames(entry)
    }
}

@Composable
private fun downloadNames(entry: OldDownloadItem) {
    if (entry.downloadNames.isNotEmpty()) {
        Text(
            entry.downloadNames.joinToString("、"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
