package com.example.pulsar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pulsar.download.DownloadActionState
import com.example.pulsar.download.buildDownloadTarget
import com.example.pulsar.download.downloadDeletedMessage
import com.example.pulsar.domain.model.Track
import com.example.pulsar.ui.components.DeleteProgressNotice
import com.example.pulsar.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 专辑 / 播放列表详情页的「下载」按钮（工单 05 / 13 / #5）。
 *
 * **这里只画**：五态由 [com.example.pulsar.download.DownloadLibrary.stateFor] 决定，
 * 删除走 `DownloadLibrary.delete(...)`。按钮不再自己读名单、查字节或写名单——
 * 那套规则在 download module 里只有一份。
 *
 * 五态：检查中 →「检查中…」（禁用）；未下载 →「下载」；下载中 → 进度环 +「取消」；
 * 已下载 →「已下载」（点开可删）；部分失败 →「N 首失败 · 重试」。
 *
 * 删除确认后在对话框里显示进度，删完关框并通过 [onMessage] 报告「已删除 N 首」（用户故事 62）。
 * 蜂窝流量下点「下载」/「重试」都先弹一次确认（用户故事 6 / 60）；Wi-Fi 下不弹。
 * **所有入队入口都走 [requestDownload]**——蜂窝确认只在一处判断，不给入口留后门。
 * （入队目前仍暂走旧 scheduler 路径，#6 再统一进 module。）
 */
@Composable
internal fun DownloadActionButton(
    kind: DownloadKind,
    id: String,
    name: String,
    tracks: List<Track>,
    serverUrl: String,
    streamUrl: (Track) -> String?,
    currentTrackId: String?,
    /** 动作结果提示（目前只有删除完成）：交给调用方弹 Snackbar。 */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val application = context.applicationContext as PulsarApplication
    val scheduler = remember(application) { application.downloadScheduler }
    val library = remember(application) { application.downloadLibrary }
    val sessions = remember(application) { application.sessionRepository() }

    // streamUrl 是 viewModel 的方法引用，每次重组都是新对象；不放进 remember 的 key，
    // 否则每次都重建 target。用 rememberUpdatedState 拿最新的那份。onMessage 同理。
    val currentStreamUrl by rememberUpdatedState(streamUrl)
    val currentOnMessage by rememberUpdatedState(onMessage)

    // 归属（serverUrl + 用户 ID）从会话取：target 需要知道这批字节属于谁。
    // null = 还没读出来（此时按钮停在「检查中…」）；
    // "" = 已确认没登录 / 读不出来（收口为未下载，按钮禁用的「下载」）。
    val userId by produceState<String?>(initialValue = null) {
        value = try {
            sessions.load()?.userId.orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 存储坏了按没登录处理：按钮给禁用的「下载」，不能永远转圈
            ""
        }
    }

    val target = remember(kind, id, name, tracks, serverUrl, userId) {
        buildDownloadTarget(
            serverUrl = serverUrl,
            userId = userId.orEmpty(),
            kind = kind,
            id = id,
            name = name,
            tracks = tracks,
            streamUrl = currentStreamUrl
        )
    }

    // 五态全部来自 module；target 为空时按「会话是否已读出来」收口：
    // 还没读出来 = 检查中，读出来但没有可下的东西 = 未下载（按钮禁用）。
    val stateFlow = remember(target, userId, library) {
        target?.let(library::stateFor) ?: MutableStateFlow(
            if (userId == null) DownloadActionState.Checking else DownloadActionState.NotDownloaded
        )
    }
    val actionState by stateFlow.collectAsStateWithLifecycle()
    // UI 边界做一次映射，五态视觉与文案保持改前一字不差。
    val state = actionState.toLegacyRenderState()

    var showCellularConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun enqueue() {
        target?.let(scheduler::enqueue)
    }

    /** 唯一的入队漏斗：蜂窝流量下先问一次，Wi-Fi 直接下。重试与首次下载共用它。 */
    fun requestDownload() {
        if (isOnCellular(context)) showCellularConfirm = true else enqueue()
    }

    fun confirmDelete() {
        val deleteTarget = target ?: return
        scope.launch {
            deleting = true
            var deleted = 0
            try {
                deleted = library.delete(deleteTarget, currentTrackId).deletedCount
            } finally {
                // 成没成都要收尾：卡在「删除中…」的对话框连返回键都关不掉。
                deleting = false
                showDeleteConfirm = false
            }
            // 报的是**真删掉的副本数**：正在播的那首被跳过就不算它。
            downloadDeletedMessage(deleted)?.let(currentOnMessage)
        }
    }

    when (state) {
        // 检查中（工单 13）：字节真相还没查出来，禁用按钮防止手快重复入队。
        null -> FilledTonalButton(onClick = {}, enabled = false, modifier = modifier) {
            CircularProgressIndicator(Modifier.size(Dimens.SpaceMedium), strokeWidth = 2.dp)
            Spacer(Modifier.width(Dimens.SpaceSmall))
            Text("检查中…")
        }

        is DownloadState.Downloading -> FilledTonalButton(
            onClick = { target?.let(scheduler::cancel) },
            modifier = modifier
        ) {
            val fraction = downloadProgressFraction(state)
            if (fraction == null) {
                CircularProgressIndicator(Modifier.size(Dimens.SpaceMedium), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(Dimens.SpaceMedium),
                    strokeWidth = 2.dp
                )
            }
            Spacer(Modifier.width(Dimens.SpaceSmall))
            Text("取消")
        }

        DownloadState.Downloaded -> FilledTonalButton(
            onClick = { if (target != null) showDeleteConfirm = true },
            modifier = modifier
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(Dimens.SpaceMedium))
            Spacer(Modifier.width(Dimens.SpaceSmall))
            Text("已下载")
        }

        // 接口只回 Boolean（`MediaDownloads.download` 把异常吞成 false），拿不到 ENOSPC，
        // 所以区分不出「手机存储空间不够」，统一按「下载失败」说（工单 05 要求的降级）。
        // 强调级与其它三态一致（用户故事 61）：同一个 FilledTonalButton，用 errorContainer 表意失败。
        is DownloadState.PartiallyFailed -> FilledTonalButton(
            onClick = ::requestDownload,
            modifier = modifier,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(Dimens.SpaceMedium))
            Spacer(Modifier.width(Dimens.SpaceSmall))
            Text("${state.failed} 首失败 · 重试")
        }

        else -> FilledTonalButton(onClick = ::requestDownload, enabled = target != null, modifier = modifier) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(Dimens.SpaceMedium))
            Spacer(Modifier.width(Dimens.SpaceSmall))
            Text("下载")
        }
    }

    if (showCellularConfirm) {
        AlertDialog(
            onDismissRequest = { showCellularConfirm = false },
            title = { Text("用移动数据下载？") },
            text = {
                Text(
                    "这会在移动数据下用约 ${formatBytes(estimateDownloadBytes(tracks))}。\n" +
                        "建议连上 Wi-Fi 再下载。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCellularConfirm = false
                        enqueue()
                    }
                ) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { showCellularConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) showDeleteConfirm = false },
            title = { Text("删除下载？") },
            text = {
                Column {
                    Text(
                        "将删掉《$name》在手机上的下载（只删当初下的那批）。\n" +
                            "歌还在电脑上一首没动；正在播放的那首会跳过。"
                    )
                    // 删除进度（用户故事 62）：逐首删要时间，静悄悄地把按钮变灰没人看得懂。
                    if (deleting) DeleteProgressNotice()
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = ::confirmDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 五态 → 旧渲染入参：只在 UI 边界映射一次，视觉/文案完全不动。 */
private fun DownloadActionState.toLegacyRenderState(): DownloadState? = when (this) {
    DownloadActionState.Checking -> null
    DownloadActionState.NotDownloaded -> DownloadState.NotDownloaded
    is DownloadActionState.Downloading -> DownloadState.Downloading(downloadedBytes, totalBytes)
    DownloadActionState.Downloaded -> DownloadState.Downloaded
    is DownloadActionState.PartiallyFailed -> DownloadState.PartiallyFailed(failed)
}

/** 估算体积用的码率：320 kbps ≈ 40 KB/s。只用于「约 N MB」的提示，不追求精确。 */
internal const val kDownloadEstimateBytesPerSecond = 40_000L

/** 估算这批曲目要下多少字节；时长未知的曲目按 0 算。 */
internal fun estimateDownloadBytes(tracks: List<Track>): Long =
    tracks.sumOf { it.durationMs ?: 0L } * kDownloadEstimateBytesPerSecond / 1000

/** 进度环的比例；总长未知（服务端没给 Content-Length）时返回 null，界面画不确定进度。 */
internal fun downloadProgressFraction(state: DownloadState.Downloading): Float? =
    if (state.totalBytes <= 0L) null
    else (state.downloadedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)

/** 当前是不是走蜂窝流量（用户故事 6：只在移动数据下弹确认，Wi-Fi 不打扰）。 */
internal fun isOnCellular(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}
