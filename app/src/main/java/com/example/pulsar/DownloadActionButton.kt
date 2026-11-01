package com.example.pulsar

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.pulsar.domain.model.Track
import com.example.pulsar.ui.theme.Dimens
import kotlinx.coroutines.launch

/**
 * 专辑 / 播放列表详情页的「下载」按钮（工单 05）。
 *
 * 四态：未下载 →「下载」；下载中 → 进度环 +「取消」；已下载 →「已下载」（点开可删）；
 * 部分失败 →「N 首失败 · 重试」。
 *
 * **已下载的判定是字节真相**（ADR-0007 决定 4）：调度器的状态只覆盖本次运行，
 * App 重启后仍要逐首问 [MediaDownloads.hasCompleteCopy]，全齐才算「已下载」。
 * 所以这里同时看两样：调度器的实时状态（下载中 / 部分失败）+ 字节真相（完成）。
 *
 * 删除**按名单删**（用户故事 10）：从 [DownloadLedgerStore] 取这条 entry 的 `trackIds`，
 * 只删当初下的那批；服务器后来新增的歌不在名单里，不会连带没了。正在播的那首跳过
 * （不把正在听的歌从播放流底下抽走），它的 id 留在名单里，下次还能删。
 *
 * 蜂窝流量下点「下载」先弹一次确认（用户故事 6）；Wi-Fi 下不弹。
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val application = context.applicationContext as PulsarApplication
    val scheduler = remember(application) { application.downloadScheduler }
    val downloads = remember(application) { application.mediaDownloads() }
    val ledger = remember(application) { application.downloadLedgerStore() }
    val sessions = remember(application) { application.sessionRepository() }

    // streamUrl 是 viewModel 的方法引用，每次重组都是新对象；不放进 remember 的 key，
    // 否则每次都重建 target。用 rememberUpdatedState 拿最新的那份。
    val currentStreamUrl by rememberUpdatedState(streamUrl)

    // 归属（serverUrl + 用户 ID）从会话取：名单按账号隔离，写错账号会让别人看见。
    val userId by produceState<String?>(initialValue = null) { value = sessions.load()?.userId }

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
    val liveStates by scheduler.state.collectAsStateWithLifecycle()
    val live = target?.let { liveStates[it.targetId] }

    // 字节真相：target 变了、删完下载、或调度器进入终态时重算一次。
    // 不按每次进度回调重算——那会把 IO 查询打成一片。
    var allComplete by remember { mutableStateOf(false) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(target, refreshEpoch, downloadStateSignature(live)) {
        allComplete = target != null && target.tracks.isNotEmpty() &&
            target.tracks.all { downloads.hasCompleteCopy(it.key) }
    }
    val state = resolveDownloadState(live, allComplete)

    var showCellularConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<DownloadEntry?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun enqueue() {
        target?.let(scheduler::enqueue)
    }

    /** 点「下载」：蜂窝流量下先问一次，Wi-Fi 直接下。 */
    fun requestDownload() {
        if (isOnCellular(context)) showCellularConfirm = true else enqueue()
    }

    /** 点「已下载」：把这条的名单查出来，确认后按名单删。 */
    fun openDeleteConfirm() {
        scope.launch {
            val uid = userId ?: return@launch
            val entries = ledger.entriesFor(serverUrl, uid)
            // id 之外再按名字兜一次：迁移来的老条目 id 是专辑名（历史遗留，可能还有）。
            deleteEntry = entries.firstOrNull { it.kind == kind && (it.id == id || it.name == name) }
            showDeleteConfirm = true
        }
    }

    fun confirmDelete() {
        scope.launch {
            deleting = true
            val entry = deleteEntry
            // 名单是删除的唯一依据；真找不到（如迁移失败）才退回「当前列表里已有完整副本的那几首」，
            // 这样不会把服务器新增、从没下过的歌也算进来。
            val trackIds = entry?.trackIds
                ?: target?.tracks?.filter { downloads.hasCompleteCopy(it.key) }?.map { it.id }
                    .orEmpty()
            downloadKeysToDelete(trackIds, tracks, currentTrackId, currentStreamUrl)
                .forEach { downloads.remove(it) }
            if (entry != null) {
                // 名单跟着改：删干净就把这条去掉，留了正在播的那首就只留它。
                val others = ledger.load().entries.filterNot { it == entry }
                val remaining = trackIds.filter { it == currentTrackId }
                ledger.replaceAll(if (remaining.isEmpty()) others else others + entry.copy(trackIds = remaining))
            }
            deleting = false
            deleteEntry = null
            showDeleteConfirm = false
            // 触发字节真相重算：调度器里可能还留着上一次的 Downloaded 终态。
            refreshEpoch++
        }
    }

    when (state) {
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

        DownloadState.Downloaded -> FilledTonalButton(onClick = ::openDeleteConfirm, modifier = modifier) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(Dimens.SpaceMedium))
            Spacer(Modifier.width(Dimens.SpaceSmall))
            Text("已下载")
        }

        // 接口只回 Boolean（`MediaDownloads.download` 把异常吞成 false），拿不到 ENOSPC，
        // 所以区分不出「手机存储空间不够」，统一按「下载失败」说（工单 05 要求的降级）。
        is DownloadState.PartiallyFailed -> TextButton(onClick = ::enqueue, modifier = modifier) {
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
                Text(
                    "将删掉《$name》在手机上的下载（只删当初下的那批）。\n" +
                        "歌还在电脑上一首没动；正在播放的那首会跳过。"
                )
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = ::confirmDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 调度器状态的「种类」签名：用来当 LaunchedEffect 的 key，避免每次进度回调都触发重算。 */
internal fun downloadStateSignature(state: DownloadState?): String = when (state) {
    null, DownloadState.NotDownloaded -> "none"
    is DownloadState.Downloading -> "downloading"
    DownloadState.Downloaded -> "downloaded"
    is DownloadState.PartiallyFailed -> "failed:${state.failed}"
    DownloadState.Canceled -> "canceled"
}

/**
 * 界面上的四态：下载中 / 部分失败直接照抄调度器（那是本次运行的事实）；
 * 其余一律按**字节真相**收口——全齐才是「已下载」，其余（含取消、删完之后、
 * 上一次运行留下的 Downloaded）都算未下载。
 *
 * 用户故事 15：已下载的专辑**不提示新增**，所以这里没有「有更新」这一态。
 */
internal fun resolveDownloadState(live: DownloadState?, allTracksComplete: Boolean): DownloadState =
    when (live) {
        is DownloadState.Downloading -> live
        is DownloadState.PartiallyFailed -> live
        else -> if (allTracksComplete) DownloadState.Downloaded else DownloadState.NotDownloaded
    }

/**
 * 由当前曲目表算下载目标。算不出播放 URL 的曲目跳过；一首都算不出（未登录 / 空专辑）
 * 返回 null —— 界面据此显示禁用的「下载」。
 */
internal fun buildDownloadTarget(
    serverUrl: String,
    userId: String,
    kind: DownloadKind,
    id: String,
    name: String,
    tracks: List<Track>,
    streamUrl: (Track) -> String?
): DownloadTarget? {
    if (serverUrl.isBlank() || userId.isBlank() || id.isBlank()) return null
    val items = tracks.mapNotNull { track ->
        // 下载 URL 与缓存键是同一个播放 URL（token 只走 header，不进 URL）。
        val url = streamUrl(track) ?: return@mapNotNull null
        DownloadTrack(id = track.id, url = url, key = url)
    }
    if (items.isEmpty()) return null
    return DownloadTarget(
        serverUrl = serverUrl,
        userId = userId,
        kind = kind,
        id = id,
        name = name,
        tracks = items
    )
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

/**
 * 按名单算出要删的缓存键：只删 [trackIds] 里点名的那些，**跳过正在播放的那首**。
 * 名单里有、当前曲目表里认不出的 id（歌被删了 / 换了服务器）直接忽略——算不出键就删不了。
 */
internal fun downloadKeysToDelete(
    trackIds: List<String>,
    tracks: List<Track>,
    playingTrackId: String?,
    streamUrl: (Track) -> String?
): List<String> {
    val byId = tracks.associateBy { it.id }
    return trackIds.mapNotNull { trackId ->
        if (trackId == playingTrackId) return@mapNotNull null
        val track = byId[trackId] ?: return@mapNotNull null
        streamUrl(track)
    }
}

/** 当前是不是走蜂窝流量（用户故事 6：只在移动数据下弹确认，Wi-Fi 不打扰）。 */
internal fun isOnCellular(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}
