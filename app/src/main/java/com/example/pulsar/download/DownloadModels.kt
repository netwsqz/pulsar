package com.example.pulsar.download

import com.example.pulsar.DownloadKind
import com.example.pulsar.DownloadState
import com.example.pulsar.DownloadTarget
import com.example.pulsar.DownloadTargetId
import com.example.pulsar.DownloadTrack
import com.example.pulsar.domain.model.Track

/**
 * 「下载归属」的账号作用域：服务器地址 + 用户 ID。
 *
 * 字节是共享的（缓存键 = 播放 URL，不带用户 ID），可见性才按它隔离（ADR-0007）。
 * 先用值类型，来源暂时由 `SessionRepository` 提供；candidate 6 落地后可换成统一的
 * `SessionScope`，这里不动。
 */
data class AccountScope(
    val serverUrl: String,
    val userId: String
)

/**
 * 设置页的下载统计。**账号无关**：它说的是一整块共享字节占了多大、有多少首完整副本，
 * 不区分这些字节当初是哪个账号下的（ADR-0007：字节共享）。
 */
data class DownloadStats(
    val usedBytes: Long,
    val completeCount: Int
)

/**
 * 「已下载」的唯一真相（`docs/download-library-refactor.md` §5）。
 *
 * 字段按「共享 → 账号相关 → 统计 → 本次运行」排列，读的时候就能分清哪些是全局的：
 * - [completeKeys]：字节完整的全部缓存键，**账号无关**（共享字节）；
 * - [visibleTrackIds]：当前账号可见的曲目 id = 字节完整 ∩ 当前账号名单；
 * - [stats]：账号无关的占用统计；
 * - [running]：调度器本次运行的状态，直接照抄，不复制重试/队列语义。
 */
data class DownloadSnapshot(
    /** 字节完整的全部缓存键（共享，账号无关）。 */
    val completeKeys: Set<String> = emptySet(),
    /** 当前账号可见的曲目 id = completeKeys ∩ ledger。null 账号时为空。 */
    val visibleTrackIds: Set<String> = emptySet(),
    /** 设置页：占用字节 / 完整副本首数。 */
    val stats: DownloadStats = DownloadStats(usedBytes = 0L, completeCount = 0),
    /** 每个 target 的本次运行状态（直接取 scheduler 的 Map）。 */
    val running: Map<DownloadTargetId, DownloadState> = emptyMap()
)

/**
 * 下载按钮的目标状态（五态）。由 [DownloadLibrary.stateFor] 输出，界面只负责画。
 */
sealed interface DownloadActionState {
    /** 字节还没查完：按钮显示「检查中…」，不先闪一个可点的「下载」。 */
    data object Checking : DownloadActionState

    data object NotDownloaded : DownloadActionState

    /** [totalBytes] 为 0 表示总长还没拿到（服务端没给 Content-Length）。 */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadActionState

    data object Downloaded : DownloadActionState

    /** 有 [failed] 首重试 3 次仍失败被跳过；其余照常下完。 */
    data class PartiallyFailed(val failed: Int) : DownloadActionState
}

/**
 * 一次删除的结果。
 *
 * [deletedCount] 是**真正跳过正在播的曲目之后**删掉的副本数，界面拿它弹「已删除 N 首」；
 * [ledgerUpdated] 表示这次删除是否按名单更新了账本（名单缺失 / 写失败时是 false，
 * 走的是「只删当前目标里已有完整副本的那几首」的兜底）。
 */
data class DownloadDeleteResult(
    val deletedCount: Int,
    val ledgerUpdated: Boolean
)

/**
 * 由当前曲目表算下载目标。算不出播放 URL 的曲目跳过；一首都算不出（未登录 / 空专辑）
 * 返回 null —— 界面据此显示禁用的「下载」。
 *
 * 放在 download 包而不是 UI 文件：按钮、ViewModel 的删除入口和测试都从这里取同一份规则。
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

/** 删除完成后的提示（用户故事 62）：删掉几首说几首；一首都没删掉（只剩正在播的那首）就不打扰。 */
internal fun downloadDeletedMessage(deleted: Int): String? =
    if (deleted <= 0) null else "已删除 $deleted 首"
