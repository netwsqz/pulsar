package com.example.pulsar.download

import com.example.pulsar.DownloadLedgerStore
import com.example.pulsar.DownloadScheduler
import com.example.pulsar.MediaDownloads
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 「已下载」的唯一真相（`docs/download-library-refactor.md` Step 1）。
 *
 * 口径只有一条：
 *
 * > **已下载 = 字节完整（缓存键）∩ 当前账号的下载名单（曲目 id）**
 *
 * 字节是共享的（缓存键 = 播放 URL，不带用户 ID），名单才分账号（ADR-0007）。
 * 两边单位不同，所以 [refresh] 必须收一次「曲目 → 缓存键」的映射，在本 module 内
 * 算完交集再交给界面——**不要**在 ViewModel / UI 里再写一份同样的交集逻辑。
 *
 * 本类只包一层，不改字节层实现，也不复制 [DownloadScheduler] 的队列 / 重试语义：
 * - [DownloadSnapshot.completeKeys] / [DownloadSnapshot.stats] 直接来自 [downloads]；
 * - [DownloadSnapshot.running] 直接取 [scheduler] 的本次运行状态；
 * - 入队 / 取消 / 删除 / 五态接线在 #5、#6 才做。
 *
 * **兜底（与改前 ViewModel 口径一致，测试已钉死）**：
 * - 账号为空（退出登录）→ 可见集合为空，但字节与统计不丢；
 * - 名单未注入 / 读失败 → 退化成只看字节真相（没有归属信息，宁可多显示不误筛）；
 * - 字节层读失败 → 空集合 / 0 统计，绝不把异常抛给界面；
 * - `CancellationException` 一律重新抛出。
 */
class DownloadLibrary(
    private val downloads: MediaDownloads,
    /** null = 没有名单（未注入）。此时可见性退化成「只看字节真相」。 */
    private val ledger: DownloadLedgerStore?,
    private val scheduler: DownloadScheduler,
    /** 留给 #5/#6 的入队、删除与账号切换后的后台重算；本步不启动任何协程。 */
    @Suppress("unused") private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DownloadSnapshot())
    val state: StateFlow<DownloadSnapshot> = _state.asStateFlow()

    /** 当前账号作用域。登录 / 切号 / 退出时由 ViewModel 更新，来源是当前会话。 */
    @Volatile
    private var account: AccountScope? = null

    /**
     * 切换账号作用域（登录 / 切号 / 退出）。旧账号的可见集合立刻作废；
     * **字节与统计不动**（ADR-0007：退出登录不清下载）。
     *
     * 可见集合依赖曲目表，没法在这里算；下一次 [refresh] 会用新账号重算。
     */
    fun setAccount(scope: AccountScope?) {
        account = scope
        _state.update { it.copy(visibleTrackIds = emptySet()) }
    }

    /**
     * 重算一次快照：字节真相、当前账号可见集合、设置页统计、调度器运行状态。
     *
     * [tracks] 是「当前可能出现在界面上的全部曲目」，[cacheKeyOf] 把曲目映射成
     * 缓存键（生产是播放流 URL，算不出返回 null）。两者都由调用方提供——
     * module 不反向依赖 ViewModel。
     */
    suspend fun refresh(
        tracks: List<Track>,
        cacheKeyOf: (Track) -> String?,
    ): DownloadSnapshot {
        // 三个读取彼此独立：某一项失败只降级那一项，不拖垮整份快照。
        val completeKeys = readCompleteKeys()
        val stats = readStats()
        val snapshot = DownloadSnapshot(
            completeKeys = completeKeys,
            visibleTrackIds = visibleTrackIds(tracks, cacheKeyOf, completeKeys),
            stats = stats,
            running = scheduler.state.value,
        )
        _state.value = snapshot
        return snapshot
    }

    private suspend fun readCompleteKeys(): Set<String> = try {
        downloads.completeKeys()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        // 字节层整体坏掉 = 判不出来 = 全部按没下载处理（铁律 1：不抛给界面）
        emptySet()
    }

    private suspend fun readStats(): DownloadStats = try {
        DownloadStats(
            usedBytes = downloads.usedBytes(),
            completeCount = downloads.completeCount(),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        DownloadStats(usedBytes = 0L, completeCount = 0)
    }

    private suspend fun visibleTrackIds(
        tracks: List<Track>,
        cacheKeyOf: (Track) -> String?,
        completeKeys: Set<String>,
    ): Set<String> {
        // 没人登录：什么都不该可见。字节仍在盘上，但归属无从谈起。
        val currentAccount = account ?: return emptySet()
        // null = 名单不可用（未注入 / 读失败）→ 退化成只看字节真相。
        val ledgerTrackIds = readLedgerTrackIds(currentAccount)
        return tracks.asSequence()
            .filter { track ->
                val key = cacheKeyOf(track) ?: return@filter false
                key in completeKeys && (ledgerTrackIds == null || track.id in ledgerTrackIds)
            }
            .mapTo(mutableSetOf()) { it.id }
    }

    /** null = 名单不可用：调用方据此跳过可见性过滤（与改前 `visibleDownloadedTrackIds` 一致）。 */
    private suspend fun readLedgerTrackIds(scope: AccountScope): Set<String>? {
        val store = ledger ?: return null
        return try {
            store.entriesFor(scope.serverUrl, scope.userId)
                .flatMap { it.trackIds }
                .toSet()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
    }
}
