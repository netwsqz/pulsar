package com.example.pulsar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 待下载的一首。URL 与缓存键都由调用方从 `streamUrl(track)` 推导后传进来——
 * 调度器不依赖 ViewModel，只认这两样东西（工单 04）。 */
data class DownloadTrack(
    val id: String,
    val url: String,
    val key: String,
)

/** 下载的最小单位：一张专辑或一个播放列表。 */
data class DownloadTarget(
    val serverUrl: String,
    val userId: String,
    val kind: DownloadKind,
    val id: String,
    val name: String,
    /** 点下载那一刻的全部曲目，原样写进名单（serverUrl/userId 也从这里取归属）。 */
    val tracks: List<DownloadTrack>,
)

/** 状态的键。用 kind+id 而不是整个 [DownloadTarget]：取消时调用方手里的曲目表可能已经变了。 */
data class DownloadTargetId(val kind: DownloadKind, val id: String)

val DownloadTarget.targetId: DownloadTargetId
    get() = DownloadTargetId(kind, id)

/** 一个目标在界面上的样子。进度是**本次运行**的聚合，历史已完成曲目不计入分母。 */
sealed interface DownloadState {
    data object NotDownloaded : DownloadState
    /** [totalBytes] 为 0 表示总长还没拿到（服务端没给 Content-Length）。 */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data object Downloaded : DownloadState
    /** 有 [failed] 首重试 3 次仍失败被跳过；其余照常下完。 */
    data class PartiallyFailed(val failed: Int) : DownloadState
    data object Canceled : DownloadState
}

/**
 * 下载调度器：串行、后台、可取消。
 *
 * - **串行**：内部一把 [Mutex]，一次只跑一个目标；第二个 [enqueue] 只会排队（ADR-0007：
 *   避免抢带宽影响播放，USB 独占下尤其容易爆音）。
 * - **只补缺口**：每首先问 [MediaDownloads.hasCompleteCopy]，完整的直接跳过。取消后
 *   再点一次就自动只下剩下的（字节真相，spec 铁律 3）。
 * - **失败重试 3 次**，退避 1s / 2s / 4s，仍失败只跳过这一首、继续下一首。
 * - **失败不抛、不弹框**（铁律 4）：结果只体现在 [state] 里。
 * - 作用域由调用方注入（生产是 [PulsarApplication] 的进程级 IO scope，测试是
 *   `TestScope.backgroundScope`），调度器自己不建 scope。
 *
 * 名单在 [enqueue] 时就写（`trackIds` = 当时全部曲目），写入不受串行队列阻塞——
 * 名单只回答「打算下哪些」，完成状态一律现算。
 */
class DownloadScheduler(
    private val scope: CoroutineScope,
    private val downloads: MediaDownloads,
    private val ledger: DownloadLedgerStore,
) {
    private val _state = MutableStateFlow<Map<DownloadTargetId, DownloadState>>(emptyMap())
    val state: StateFlow<Map<DownloadTargetId, DownloadState>> = _state.asStateFlow()

    /** 串行队列：一次一个目标。 */
    private val queueMutex = Mutex()

    // enqueue 在界面线程、完成回调在 IO 线程，用并发 map 免得两边同时改
    private val jobs = java.util.concurrent.ConcurrentHashMap<DownloadTargetId, Job>()

    /**
     * 入队。同一个目标重复入队 = 重新跑一遍（已完整的曲目会被跳过，所以天然是「补缺口」）。
     * 正在排队/下载的旧任务先取消——旧任务已下完的部分不会回滚。
     */
    fun enqueue(target: DownloadTarget) {
        val id = target.targetId
        jobs.remove(id)?.cancel()
        _state.update { it + (id to DownloadState.Downloading(0, 0)) }

        // 名单立刻写，不等队列（排队中的目标也算「打算下」）。
        scope.launch { recordInLedger(target) }

        val job = scope.launch {
            // 排队期间被取消就别再开始
            if (!isActive) return@launch
            queueMutex.withLock {
                if (!isActive) return@withLock
                runTarget(target)
            }
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    /** 取消排队或下载中的目标；**已下完的曲目保留**（下次重下只补缺口）。 */
    fun cancel(target: DownloadTarget) {
        val id = target.targetId
        jobs.remove(id)?.cancel()
        _state.update { current ->
            if (current[id] == DownloadState.Downloaded) current
            else current + (id to DownloadState.Canceled)
        }
    }

    private suspend fun recordInLedger(target: DownloadTarget) {
        val entry = DownloadEntry(
            serverUrl = target.serverUrl,
            userId = target.userId,
            kind = target.kind,
            id = target.id,
            name = target.name,
            trackIds = target.tracks.map { it.id },
            createdAt = System.currentTimeMillis(),
        )
        // 名单写入只走 store 的唯一入口（#6）：scheduler 不再自己持锁。
        // 生产注入的是 SerializedDownloadLedgerStore，与 DownloadLibrary / ViewModel 共用同一把锁。
        ledger.update { entries ->
            val others = entries.filterNot {
                it.serverUrl == entry.serverUrl && it.userId == entry.userId &&
                    it.kind == entry.kind && it.id == entry.id
            }
            others + entry
        }
    }

    /**
     * 跑完一个目标。进度只聚合**本次运行**尝试过的曲目：已完成带动总长的按满算，
     * 当前曲目按回调实时算。被跳过的（本来就有完整副本）不进分母。
     */
    private suspend fun runTarget(target: DownloadTarget) {
        val id = target.targetId
        var doneBytes = 0L
        var doneTotal = 0L
        var currentBytes = 0L
        var currentTotal = 0L
        var failed = 0

        fun publish() {
            _state.update {
                it + (id to DownloadState.Downloading(doneBytes + currentBytes, doneTotal + currentTotal))
            }
        }

        for (track in target.tracks) {
            if (downloads.hasCompleteCopy(track.key)) continue

            currentBytes = 0L
            currentTotal = 0L
            val ok = downloadWithRetry(track) { downloaded, total ->
                currentBytes = downloaded
                if (total > 0) currentTotal = total
                publish()
            }
            if (ok) {
                // 已完成曲目按最后一次回调的字节累计（总长未知时 total 保持 0）
                doneBytes += currentBytes
                doneTotal += currentTotal
            } else {
                failed++
            }
            currentBytes = 0L
            currentTotal = 0L
            publish()
        }

        _state.update {
            it + (id to if (failed > 0) DownloadState.PartiallyFailed(failed) else DownloadState.Downloaded)
        }
    }

    /** 一首最多下 4 次（首次 + 重试 3 次），退避 1s / 2s / 4s。取消照常上抛。 */
    private suspend fun downloadWithRetry(
        track: DownloadTrack,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        for (attempt in 0..kRetryBackoffMillis.size) {
            if (downloads.download(track.url, track.key, onProgress)) return true
            if (attempt < kRetryBackoffMillis.size) delay(kRetryBackoffMillis[attempt])
        }
        return false
    }

    private companion object {
        /** 重试前的等待：1s / 2s / 4s。 */
        val kRetryBackoffMillis = longArrayOf(1_000, 2_000, 4_000)
    }
}
