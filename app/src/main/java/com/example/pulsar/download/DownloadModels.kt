package com.example.pulsar.download

import com.example.pulsar.DownloadState
import com.example.pulsar.DownloadTargetId

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
 * 下载按钮的目标状态（五态）。**本票只定义类型，不接线**；`stateFor(target)` 与
 * `DownloadActionButton` 的收口在 #5（Step 2）才接。
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
