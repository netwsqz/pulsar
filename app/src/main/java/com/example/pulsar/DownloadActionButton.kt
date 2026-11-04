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
import com.example.pulsar.ui.components.DeleteProgressNotice
import com.example.pulsar.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 专辑 / 播放列表详情页的「下载」按钮（工单 05 / 13）。
 *
 * 五态：检查中 →「检查中…」（禁用）；未下载 →「下载」；下载中 → 进度环 +「取消」；
 * 已下载 →「已下载」（点开可删）；部分失败 →「N 首失败 · 重试」。
 *
 * **已下载的判定是字节真相**（ADR-0007 决定 4）：调度器的状态只覆盖本次运行，
 * App 重启后仍要逐首问 [MediaDownloads.hasCompleteCopy]，全齐才算「已下载」。
 * 所以这里同时看两样：调度器的实时状态（下载中 / 部分失败）+ 字节真相（完成）。
 * 字节还没查完的那一下**不猜**——界面显示禁用的「检查中…」（用户故事 59），
 * 否则打开一张已下载的专辑会先闪一个可点的「下载」，手快就重复入队了。
 *
 * 删除**按名单删**（用户故事 10）：从 [DownloadLedgerStore] 取这条 entry 的 `trackIds`，
 * 只删当初下的那批；服务器后来新增的歌不在名单里，不会连带没了。正在播的那首跳过
 * （不把正在听的歌从播放流底下抽走），它的 id 留在名单里，下次还能删。
 * 确认后在对话框里显示进度，删完关框并通过 [onMessage] 报告「已删除 N 首」（用户故事 62）。
 *
 * 蜂窝流量下点「下载」/「重试」都先弹一次确认（用户故事 6 / 60）；Wi-Fi 下不弹。
 * **所有入队入口都走 [requestDownload]**——蜂窝确认只在一处判断，不给入口留后门。
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
    val downloads = remember(application) { application.mediaDownloads() }
    val ledger = remember(application) { application.downloadLedgerStore() }
    val sessions = remember(application) { application.sessionRepository() }

    // streamUrl 是 viewModel 的方法引用，每次重组都是新对象；不放进 remember 的 key，
    // 否则每次都重建 target。用 rememberUpdatedState 拿最新的那份。onMessage 同理。
    val currentStreamUrl by rememberUpdatedState(streamUrl)
    val currentOnMessage by rememberUpdatedState(onMessage)

    // 归属（serverUrl + 用户 ID）从会话取：名单按账号隔离，写错账号会让别人看见。
    // null = 还没读出来（此时字节属于谁都不知道，按钮停在「检查中…」）；
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
    val liveStates by scheduler.state.collectAsStateWithLifecycle()
    val live = target?.let { liveStates[it.targetId] }

    // 字节真相（工单 13）：null = 还没查完（检查中）。结论连同「算出它时的调度器状态签名」一起存，
    // 签名变了就自动作废——下载刚写完 / 被取消时字节可能已经不同，不拿旧结论硬撑那几帧，
    // 免得闪一下可点的「下载」（用户故事 59 / 63）。target 变了（换专辑、会话刚读出来）也从头查。
    var byteTruth by remember(target) { mutableStateOf<ByteTruth?>(null) }
    var refreshEpoch by remember(target) { mutableIntStateOf(0) }
    val signature = downloadStateSignature(live)
    LaunchedEffect(target, userId, refreshEpoch, signature) {
        byteTruth = ByteTruth(
            signature = signature,
            allComplete = resolveAllTracksComplete(target, sessionResolved = userId != null) {
                downloads.hasCompleteCopy(it)
            }
        )
    }
    val state = resolveDownloadState(live, byteTruth?.takeIf { it.signature == signature }?.allComplete)

    var showCellularConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<DownloadEntry?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun enqueue() {
        target?.let(scheduler::enqueue)
    }

    /** 唯一的入队漏斗：蜂窝流量下先问一次，Wi-Fi 直接下。重试与首次下载共用它。 */
    fun requestDownload() {
        if (isOnCellular(context)) showCellularConfirm = true else enqueue()
    }

    /** 点「已下载」：把这条的名单查出来，确认后按名单删。 */
    fun openDeleteConfirm() {
        scope.launch {
            val uid = userId?.takeIf { it.isNotEmpty() } ?: return@launch
            val entries = ledger.entriesFor(serverUrl, uid)
            // id 之外再按名字兜一次：迁移来的老条目 id 是专辑名（历史遗留，可能还有）。
            deleteEntry = entries.firstOrNull { it.kind == kind && (it.id == id || it.name == name) }
            showDeleteConfirm = true
        }
    }

    fun confirmDelete() {
        scope.launch {
            deleting = true
            var deleted = 0
            try {
                val entry = deleteEntry
                // 名单是删除的唯一依据；真找不到（如迁移失败）才退回「当前列表里已有完整副本的那几首」，
                // 这样不会把服务器新增、从没下过的歌也算进来。
                val trackIds = entry?.trackIds
                    ?: target?.tracks?.filter { downloads.hasCompleteCopy(it.key) }?.map { it.id }
                        .orEmpty()
                val keys = downloadKeysToDelete(trackIds, tracks, currentTrackId, currentStreamUrl)
                deleted = keys.size
                keys.forEach { downloads.remove(it) }
                if (entry != null) {
                    // 名单跟着改：删干净就把这条去掉，留了正在播的那首就只留它。
                    // 写不进去不影响删除本身（字节已经删了），跟 ViewModel 的删除同一口径。
                    try {
                        val others = ledger.load().entries.filterNot { it == entry }
                        val remaining = trackIds.filter { it == currentTrackId }
                        ledger.replaceAll(
                            if (remaining.isEmpty()) others else others + entry.copy(trackIds = remaining)
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        // 名单写不进去不影响删除本身：字节已经删了，界面按字节真相重算
                    }
                }
            } finally {
                // 成没成都要收尾：卡在「删除中…」的对话框连返回键都关不掉。
                deleting = false
                deleteEntry = null
                showDeleteConfirm = false
                // 字节变了：作废当前结论回到「检查中…」再重算，别让「已下载」多撑几帧。
                byteTruth = null
                refreshEpoch++
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

        DownloadState.Downloaded -> FilledTonalButton(onClick = ::openDeleteConfirm, modifier = modifier) {
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

/**
 * 字节真相连同它的检查口径（工单 13）。
 *
 * [signature] 是算出这份结论时的调度器状态「种类」（[downloadStateSignature]）。与当前的
 * 不一致就说明字节可能已经变了（下载刚写完 / 被取消 / 删完之后手动作废），这份结论作废，
 * 按钮回到「检查中…」——而不是拿上一次的 true/false 硬撑几帧（那会闪一下可点的「下载」）。
 */
private data class ByteTruth(val signature: String, val allComplete: Boolean?)

/** 调度器状态的「种类」签名：用来当 LaunchedEffect 的 key，避免每次进度回调都触发重算。 */
internal fun downloadStateSignature(state: DownloadState?): String = when (state) {
    null, DownloadState.NotDownloaded -> "none"
    is DownloadState.Downloading -> "downloading"
    DownloadState.Downloaded -> "downloaded"
    is DownloadState.PartiallyFailed -> "failed:${state.failed}"
    DownloadState.Canceled -> "canceled"
}

/**
 * 界面上的五态（工单 13）：检查中（`null`）/ 未下载 / 下载中 / 已下载 / 部分失败。
 *
 * 下载中与部分失败直接照抄调度器（那是本次运行的事实）；其余一律按**字节真相**收口——
 * 全齐才是「已下载」，其余（含取消、删完之后、上一次运行留下的 Downloaded）都算未下载。
 *
 * [allTracksComplete] 是**三态**（用户故事 59）：`null` = 字节还没查完，返回 `null` 表示
 * 「检查中…」。不能把未知当成未下载——那正是「打开已下载专辑先闪一下可点的下载」的来源。
 *
 * 用户故事 15：已下载的专辑**不提示新增**，所以这里没有「有更新」这一态。
 */
internal fun resolveDownloadState(live: DownloadState?, allTracksComplete: Boolean?): DownloadState? =
    when (live) {
        is DownloadState.Downloading -> live
        is DownloadState.PartiallyFailed -> live
        else -> when (allTracksComplete) {
            null -> null
            true -> DownloadState.Downloaded
            false -> DownloadState.NotDownloaded
        }
    }

/**
 * 字节真相的三态出口（用户故事 59）：`null` = 还查不出来（会话没读出来，不知道这批字节
 * 属于谁），界面据此显示禁用的「检查中…」；其余逐首问 [hasCompleteCopy]，全齐才是 `true`。
 *
 * **target 为空 / 空专辑直接收口为 `false`（未下载）**——没有可查的目标却一直「检查中…」
 * 就是转不完的圈。这两种情况按钮本来就该是禁用的「下载」。
 */
internal suspend fun resolveAllTracksComplete(
    target: DownloadTarget?,
    sessionResolved: Boolean,
    hasCompleteCopy: suspend (String) -> Boolean
): Boolean? {
    if (!sessionResolved) return null
    val keys = target?.tracks?.map { it.key } ?: return false
    if (keys.isEmpty()) return false
    return keys.all { hasCompleteCopy(it) }
}

/** 删除完成后的提示（用户故事 62）：删掉几首说几首；一首都没删掉（只剩正在播的那首）就不打扰。 */
internal fun downloadDeletedMessage(deleted: Int): String? =
    if (deleted <= 0) null else "已删除 $deleted 首"

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
