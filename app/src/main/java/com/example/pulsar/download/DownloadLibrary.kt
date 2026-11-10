package com.example.pulsar.download

import com.example.pulsar.DownloadEntry
import com.example.pulsar.DownloadLedgerStore
import com.example.pulsar.DownloadScheduler
import com.example.pulsar.DownloadState
import com.example.pulsar.DownloadTarget
import com.example.pulsar.DownloadTargetId
import com.example.pulsar.DownloadTrack
import com.example.pulsar.MediaDownloads
import com.example.pulsar.domain.model.Track
import com.example.pulsar.targetId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「已下载」的唯一真相（`docs/download-library-refactor.md` Step 1 / Step 2）。
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
 * - 按钮五态与删除（Step 2）也在这里收口，界面只管画。
 *
 * **兜底（与改前 ViewModel / 按钮口径一致，测试已钉死）**：
 * - 账号为空（还没恢复 / 已退出）→ 可见集合为空、按钮停在「检查中…」，但字节与统计不丢；
 * - 名单未注入 / 读失败 → 退化成只看字节真相（没有归属信息，宁可多显示不误筛）；
 * - 字节层读失败 → 空集合 / 0 统计 / 按钮「检查中…」，绝不把异常抛给界面；
 * - `CancellationException` 一律重新抛出。
 */
class DownloadLibrary(
    private val downloads: MediaDownloads,
    /** null = 没有名单（未注入）。此时可见性与按钮状态退化成「只看字节真相」。 */
    private val ledger: DownloadLedgerStore?,
    private val scheduler: DownloadScheduler,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DownloadSnapshot())
    val state: StateFlow<DownloadSnapshot> = _state.asStateFlow()

    /** 当前账号作用域。登录 / 切号 / 退出时由 ViewModel 更新，来源是当前会话。 */
    @Volatile
    private var account: AccountScope? = null

    /** 每个按钮目标一份五态流；界面通过 [stateFor] 取。 */
    private val actionLock = Any()
    private val actionFlows = mutableMapOf<DownloadTargetId, MutableStateFlow<DownloadActionState>>()
    private val actionTargets = mutableMapOf<DownloadTargetId, DownloadTarget>()
    private val actionSignatures = mutableMapOf<DownloadTargetId, String?>()
    private val actionRevisions = mutableMapOf<DownloadTargetId, Int>()
    private val actionJobs = mutableMapOf<DownloadTargetId, Job>()

    init {
        // 调度器每一跳都要收：进度要实时映射到按钮；只有「状态种类」变化时才重新查字节，
        // 不然每个进度回调都逐首 IO 会把按钮拖垮（Step 2 风险表）。
        scope.launch {
            scheduler.state.collect { states ->
                // 设置页的失败聚合直接读快照里的 running（#6）：这里跟着调度器走，
                // 不能停在 refresh() 那一刻。
                _state.update { it.copy(running = states) }
                val targets = synchronized(actionLock) { actionTargets.values.toList() }
                var statsDirty = false
                for (target in targets) {
                    val id = target.targetId
                    val live = states[id]
                    val current = account
                    when {
                        current == null -> setActionState(target, DownloadActionState.Checking)
                        !target.belongsTo(current) ->
                            setActionState(target, DownloadActionState.NotDownloaded)
                        live is DownloadState.Downloading -> setActionState(
                            target,
                            DownloadActionState.Downloading(live.downloadedBytes, live.totalBytes)
                        )
                        live is DownloadState.PartiallyFailed -> setActionState(
                            target,
                            DownloadActionState.PartiallyFailed(live.failed)
                        )
                    }
                    val changed = synchronized(actionLock) {
                        val signature = downloadStateSignature(live)
                        if (actionSignatures[id] != signature) {
                            actionSignatures[id] = signature
                            true
                        } else {
                            false
                        }
                    }
                    if (changed && live !is DownloadState.Downloading) {
                        // 完成 / 失败 / 取消都会改字节统计：设置页读 state.stats 就是新的。
                        statsDirty = true
                    }
                    // 下载中 / 部分失败的进度已经直接映射；其余种类变化才需要按字节重算
                    if (changed && live !is DownloadState.Downloading &&
                        live !is DownloadState.PartiallyFailed
                    ) {
                        launchActionComputation(target)
                    }
                }
                if (statsDirty) refreshStats()
            }
        }
    }

    /**
     * 切换账号作用域（登录 / 切号 / 退出）。旧账号的可见集合立刻作废；
     * **字节与统计不动**（ADR-0007：退出登录不清下载）。
     *
     * 可见集合依赖曲目表，没法在这里算；下一次 [refresh] 会用新账号重算。按钮的五态
     * 也会立刻重算：旧账号的 target 会因为作用域不一致收口成「未下载」。
     */
    fun setAccount(scope: AccountScope?) {
        account = scope
        _state.update { it.copy(visibleTrackIds = emptySet()) }
        invalidateActionStates()
    }

    // ── 入队 / 取消（#6）────────────────────────────────────────────

    /**
     * 入队一个下载目标。**界面只走这里**，不再自己拿 [DownloadScheduler]：
     * 名单写入、进度映射与统计刷新都挂在 module 上。
     */
    fun enqueue(target: DownloadTarget) = scheduler.enqueue(target)

    /**
     * 取消排队或下载中的目标；已下完的曲目保留（下次重下只补缺口）。
     * 统计会在调度器状态变化后刷新。
     */
    fun cancel(target: DownloadTarget) = scheduler.cancel(target)

    /**
     * 名单唯一的读-改-写入口（#6）。界面 / 测试要改名单都走这里——
     * 生产注入的是 [com.example.pulsar.SerializedDownloadLedgerStore]，
     * 与调度器共用同一把锁；自己 `load()` + `replaceAll()` 会丢条目。
     */
    suspend fun updateLedger(block: suspend (List<DownloadEntry>) -> List<DownloadEntry>) {
        ledger?.update(block)
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

    /**
     * 重读设置页统计（占用字节 / 完整副本首数）。
     *
     * 这是统计的**唯一入口**：设置页进页时调它，界面读 [state] 里的 `stats` 即可，
     * 不再直接问字节层。统计与前账号无关，所以不需要曲目表，也不改可见集合。
     */
    suspend fun refreshStats() {
        val stats = readStats()
        _state.update { it.copy(stats = stats) }
    }

    // ── 按钮五态（Step 2）────────────────────────────────────────────

    /**
     * 某个下载目标的五态流。第一次问某个目标时会登记并在后台查一次字节；之后只在
     * 调度器状态种类变化、账号切换、删除之后重算。
     *
     * 语义（逐条保留改前按钮）：
     * - 调度器有 `Downloading` / `PartiallyFailed` → 直接照抄（本次运行的事实）；
     * - 其余按字节真相：target 里每一首都完整 → `Downloaded`，否则 `NotDownloaded`；
     * - 字节全齐但**不在当前账号名单**里 → `NotDownloaded`（「一个真相」）；
     * - 账号还没确定 / 字节还查不出来 → `Checking`，**不能把未知当未下载**；
     * - target 与当前 [AccountScope] 不一致 → `NotDownloaded`（换账号的关键修复）。
     */
    fun stateFor(target: DownloadTarget): StateFlow<DownloadActionState> {
        val id = target.targetId
        var changed = false
        val flow = synchronized(actionLock) {
            val existing = actionFlows.getOrPut(id) {
                MutableStateFlow<DownloadActionState>(DownloadActionState.Checking)
            }
            if (actionTargets[id] != target) {
                actionTargets[id] = target
                actionSignatures[id] = downloadStateSignature(scheduler.state.value[id])
                changed = true
            }
            existing
        }
        // 只在目标本身变了/第一次登记时启动查询；重组不会反复打 IO。
        if (changed) launchActionComputation(target)
        return flow.asStateFlow()
    }

    private fun launchActionComputation(target: DownloadTarget) {
        val id = target.targetId
        // 版本号防过期：旧的一次查询回来时，如果目标已经换过 / 删过，不能拿旧结论盖新状态。
        val revision = synchronized(actionLock) {
            actionJobs.remove(id)?.cancel()
            val next = (actionRevisions[id] ?: 0) + 1
            actionRevisions[id] = next
            next
        }

        val current = account
        if (current == null) {
            setActionState(target, DownloadActionState.Checking)
            return
        }
        if (!target.belongsTo(current)) {
            setActionState(target, DownloadActionState.NotDownloaded)
            return
        }
        if (target.tracks.isEmpty()) {
            setActionState(target, DownloadActionState.NotDownloaded)
            return
        }

        // 本次运行的事实不需要查字节：下载中 / 部分失败直接照抄。
        val live = scheduler.state.value[id]
        if (live is DownloadState.Downloading) {
            setActionState(
                target,
                DownloadActionState.Downloading(live.downloadedBytes, live.totalBytes)
            )
            return
        }
        if (live is DownloadState.PartiallyFailed) {
            setActionState(target, DownloadActionState.PartiallyFailed(live.failed))
            return
        }

        // 其余先回到「检查中…」，查完再落结论；别先闪一个可点的「下载」。
        setActionState(target, DownloadActionState.Checking)
        val job = scope.launch {
            val computed = computeActionState(target)
            setActionState(target, computed, revision)
        }
        synchronized(actionLock) {
            if (actionTargets[id] == target && actionRevisions[id] == revision) {
                actionJobs[id] = job
            } else {
                job.cancel()
            }
        }
    }

    private suspend fun computeActionState(target: DownloadTarget): DownloadActionState {
        val current = account ?: return DownloadActionState.Checking
        if (!target.belongsTo(current)) return DownloadActionState.NotDownloaded
        if (target.tracks.isEmpty()) return DownloadActionState.NotDownloaded

        val allComplete = try {
            resolveAllTracksComplete(target, sessionResolved = true) {
                downloads.hasCompleteCopy(it)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 查不出来 ≠ 未下载：宁可继续显示「检查中…」，也不骗用户重点一次。
            return DownloadActionState.Checking
        } ?: return DownloadActionState.Checking
        if (!allComplete) return DownloadActionState.NotDownloaded

        // 「一个真相」：字节全齐还得在当前账号名单里。名单不可用（未注入 / 读失败）时
        // 退化成只看字节真相，与改前兜底一致。
        val ledgerTrackIds = readLedgerTrackIds(current) ?: return DownloadActionState.Downloaded
        return if (target.tracks.all { it.id in ledgerTrackIds }) {
            DownloadActionState.Downloaded
        } else {
            DownloadActionState.NotDownloaded
        }
    }

    private fun setActionState(
        target: DownloadTarget,
        state: DownloadActionState,
        revision: Int? = null,
    ) {
        synchronized(actionLock) {
            val id = target.targetId
            if (actionTargets[id] != target) return
            if (revision != null) {
                // 过期的一次查询回来了：它所在的版本已经被换掉，不盖新状态。
                if (actionRevisions[id] != revision) return
                // 查字节期间调度器已经开跑 / 已经部分失败：本次运行的事实更新，
                // 不能拿查了一半的旧字节结论把进度环盖回「未下载」。
                val live = scheduler.state.value[id]
                if (live is DownloadState.Downloading ||
                    live is DownloadState.PartiallyFailed
                ) {
                    return
                }
            }
            actionFlows[id]?.value = state
        }
    }

    private fun invalidateActionStates() {
        val targets = synchronized(actionLock) { actionTargets.values.toList() }
        targets.forEach(::launchActionComputation)
    }

    // ── 删除（Step 2）───────────────────────────────────────────────

    /**
     * 按名单删掉一个目标的下载。
     *
     * 规则与改前两处实现（按钮 / ViewModel）一致：
     * - 名单是唯一依据：只删当初下的那批，服务器后来新增的歌不在名单里，不跟着删；
     * - 正在播的那首跳过，它的 id 保留在名单里，下次还能删；
     * - 名单里找不到这条（迁移失败 / 没注入名单）时兜底到「当前目标里已有完整副本的那几首」，
     *   绝不按专辑名一锅端；
     * - 返回的 [DownloadDeleteResult.deletedCount] 是真正放行删除的副本数（跳过的不算）。
     */
    suspend fun delete(target: DownloadTarget, playingTrackId: String?): DownloadDeleteResult {
        // 换账号后旧 target 即使还挂着，也绝不能让新账号替它删共享字节。
        val current = account
        if (current == null || !target.belongsTo(current)) {
            return DownloadDeleteResult(deletedCount = 0, ledgerUpdated = false)
        }
        val entry = findLedgerEntry(target)
        val trackIds = entry?.trackIds ?: fallbackTrackIds(target)
        val keys = downloadKeysToDelete(trackIds, target.tracks, playingTrackId)
        keys.forEach { downloads.remove(it) }

        // 名单跟着改：删干净就整条去掉，留了正在播的那首就只留它。
        // 写不进去不影响删除本身（字节已经删了），跟改前同一口径。
        val keepTrackIds = trackIds.filter { it == playingTrackId }
        val ledgerUpdated = entry != null && updateLedgerAfterDelete(entry, keepTrackIds)

        // 删完立刻重算按钮状态与统计：不让「已下载」和占用数字多撑几帧。
        invalidateActionStates()
        refreshStats()
        return DownloadDeleteResult(deletedCount = keys.size, ledgerUpdated = ledgerUpdated)
    }

    /**
     * 删除单曲。[key] 是它的缓存键；正在播的那首直接拒绝（返回 false），
     * 名单里所有含这首的条目都会把这一首去掉（空条目整条移除）。
     *
     * @return 是否真的删掉了一份完整副本。
     */
    suspend fun deleteTrack(track: Track, key: String, playingTrackId: String?): Boolean {
        if (track.id == playingTrackId) return false
        if (key.isBlank()) return false
        // 没人登录（或还在切换）时不删共享字节：谁都不拥有这批下载。
        if (account == null) return false
        val hasCopy = try {
            downloads.hasCompleteCopy(key)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            false
        }
        if (!hasCopy) return false

        downloads.remove(key)
        removeTrackFromLedger(track.id)
        invalidateActionStates()
        refreshStats()
        return true
    }

    private suspend fun findLedgerEntry(target: DownloadTarget): DownloadEntry? {
        val store = ledger ?: return null
        return try {
            store.entriesFor(target.serverUrl, target.userId).firstOrNull {
                it.kind == target.kind && (it.id == target.id || it.name == target.name)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 名单读失败按「名单缺失」处理，走字节兜底
            null
        }
    }

    /** 名单缺失时的兜底：只删这个 target 里**已有完整副本**的那几首。 */
    private suspend fun fallbackTrackIds(target: DownloadTarget): List<String> = try {
        target.tracks
            .filter { downloads.hasCompleteCopy(it.key) }
            .map { it.id }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        emptyList()
    }

    private suspend fun updateLedgerAfterDelete(
        entry: DownloadEntry,
        keepTrackIds: List<String>,
    ): Boolean {
        val store = ledger ?: return false
        return try {
            // 唯一的读-改-写入口（#6）：load + replaceAll 自己拼会与调度器互相覆盖。
            store.update { entries ->
                val others = entries.filterNot { it == entry }
                if (keepTrackIds.isEmpty()) others else others + entry.copy(trackIds = keepTrackIds)
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 名单写不进去不影响删除本身：字节已经删了，界面按字节真相重算
            false
        }
    }

    /** 单曲删除后把这一首从当前账号所有名单条目里去掉；条目空了就整条移除。 */
    private suspend fun removeTrackFromLedger(trackId: String) {
        val store = ledger ?: return
        val current = account ?: return
        try {
            // 同样走唯一入口：先读再写会被调度器的入队写名单覆盖（#6）。
            store.update { entries ->
                entries.mapNotNull { entry ->
                    if (entry.serverUrl != current.serverUrl ||
                        entry.userId != current.userId ||
                        trackId !in entry.trackIds
                    ) {
                        entry
                    } else {
                        val remaining = entry.trackIds.filterNot { it == trackId }
                        if (remaining.isEmpty()) null else entry.copy(trackIds = remaining)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 名单更新失败不影响字节删除
        }
    }

    // ── 读取兜底 ────────────────────────────────────────────────────

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

/** target 的归属是否就是当前账号作用域。 */
private fun DownloadTarget.belongsTo(scope: AccountScope): Boolean =
    serverUrl == scope.serverUrl && userId == scope.userId

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

/**
 * 界面上的五态（工单 13 / #5）：检查中 / 未下载 / 下载中 / 已下载 / 部分失败。
 *
 * 下载中与部分失败直接照抄调度器（那是本次运行的事实）；其余一律按**字节真相**收口——
 * 全齐才是「已下载」，其余（含取消、删完之后、上一次运行留下的 Downloaded）都算未下载。
 *
 * [allTracksComplete] 是**三态**（用户故事 59）：`null` = 字节还没查完，返回 `Checking`。
 * 不能把未知当成未下载——那正是「打开已下载专辑先闪一下可点的下载」的来源。
 *
 * [accountMatches] = false 表示 target 属于别的账号：按 ADR-0007 一律当作未下载，
 * 连旧账号的下载进度也不显示。
 */
internal fun resolveDownloadActionState(
    live: DownloadState?,
    allTracksComplete: Boolean?,
    accountMatches: Boolean = true
): DownloadActionState = when {
    !accountMatches -> DownloadActionState.NotDownloaded
    live is DownloadState.Downloading ->
        DownloadActionState.Downloading(live.downloadedBytes, live.totalBytes)
    live is DownloadState.PartiallyFailed -> DownloadActionState.PartiallyFailed(live.failed)
    allTracksComplete == null -> DownloadActionState.Checking
    allTracksComplete -> DownloadActionState.Downloaded
    else -> DownloadActionState.NotDownloaded
}

/** 调度器状态的「种类」签名：用来判断要不要重新查字节，避免每次进度回调都触发 IO。 */
internal fun downloadStateSignature(state: DownloadState?): String = when (state) {
    null, DownloadState.NotDownloaded -> "none"
    is DownloadState.Downloading -> "downloading"
    DownloadState.Downloaded -> "downloaded"
    is DownloadState.PartiallyFailed -> "failed:${state.failed}"
    DownloadState.Canceled -> "canceled"
}

/** 按名单算出要删的缓存键：只删 [trackIds] 里点名的那些，**跳过正在播放的那首**。 */
internal fun downloadKeysToDelete(
    trackIds: List<String>,
    tracks: List<DownloadTrack>,
    playingTrackId: String?
): List<String> {
    val byId = tracks.associateBy { it.id }
    return trackIds.mapNotNull { trackId ->
        if (trackId == playingTrackId) return@mapNotNull null
        byId[trackId]?.key
    }
}
