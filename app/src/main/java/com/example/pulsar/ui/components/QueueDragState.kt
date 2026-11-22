package com.example.pulsar.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.pulsar.QueueTrack
import com.example.pulsar.playback.applyQueueOrder

/**
 * 播放队列的**拖拽暂态**（issue #28）。队列真相只有一个：`PlaybackUiState.queue`。
 *
 * 迷你条队列面板与全屏页队列页共用一个实例（由 `MusicLibraryScreen` 持有），于是：
 * - 平时 [displayed] 原样返回会话队列，界面没有自己的长期副本；
 * - 拖拽中只按 id 顺序重排会话快照，**曲目数据（含正在播标记）永远现取**，切歌两处同时动；
 * - 松手把目标顺序交给会话，并**保留暂态直到会话队列追上这个顺序**——否则播放器还在路上，
 *   界面会先闪回旧顺序再跳成新顺序。
 *
 * [start]/[move]/[finish]/[drop]/[cancel]/[onSessionQueueChanged] 都是普通方法，全部在 UI 线程
 * 调用；[isDragging]、[isPending] 与 [displayed] 读的暂态是 Compose 可观察的。
 */
@Stable
internal class QueueDragState {

    /** 拖拽中为 `true`，其余时候 `false`。 */
    var isDragging by mutableStateOf(false)
        private set

    /** 已松手、但暂态还挂着（等会话队列追上，或等超时兜底）。 */
    val isPending: Boolean
        get() = order != null && !isDragging

    /** 暂态顺序（只存 id）；`null` = 没有暂态，显示会话队列。 */
    private var order by mutableStateOf<List<String>?>(null)

    /**
     * 开始拖拽：以当前**显示**顺序为暂态起点（上一次拖拽的残留顺序还在时也不会跳一下）。
     * 已经在拖拽中时忽略，避免重复手势把顺序重置。
     */
    fun start(sessionQueue: List<QueueTrack>) {
        if (isDragging) return
        isDragging = true
        order = displayed(sessionQueue).map { it.id }
    }

    /** 有效跨位才返回 true（给界面触感用）；忽略取消／松手后迟到的回调。 */
    fun move(from: Int, to: Int, sessionQueue: List<QueueTrack>): Boolean {
        if (!isDragging || from == to) return false
        val current = displayed(sessionQueue).map { it.id }
        if (from !in current.indices || to !in current.indices) return false
        order = current.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    /**
     * 松手：结束拖拽，返回需要回写给会话的目标顺序；`null` = 顺序没变、不必回写。
     *
     * **暂态保留**到 [onSessionQueueChanged] 看到会话队列追上为止：播放器接受重排要走一轮
     * move，中间界面不能先闪回旧顺序。
     */
    fun finish(sessionQueue: List<QueueTrack>): List<QueueTrack>? {
        isDragging = false
        val current = order ?: return null
        val target = applyQueueOrder(sessionQueue, current)
        val unchanged = target.map { it.id } == sessionQueue.map { it.id }
        if (unchanged) order = null
        return if (unchanged) null else target
    }

    /**
     * 松手并把目标顺序交给 [writeBack]（返回 `false` = 意图没被接住，例如未连接）。
     * 没变化时不回写；回写失败时立刻放弃暂态，回到会话真相。两个队列面板共用这一个入口。
     */
    fun drop(sessionQueue: List<QueueTrack>, writeBack: (List<QueueTrack>) -> Boolean) {
        val ordered = finish(sessionQueue) ?: return
        if (!writeBack(ordered)) cancel()
    }

    /** 回写没被接住（未连接 / 已关闭）：直接回到会话顺序。 */
    fun cancel() {
        isDragging = false
        order = null
    }

    /**
     * 会话队列变了：只在**它已经和暂态顺序一致**时释放暂态，回到唯一真相。
     *
     * 不能「一变就放」：松手后播放器还要走一轮 move，这期间切歌（isCurrent 移动）、增删
     * 都会让会话队列变化；提前释放会让面板先闪回旧顺序、再跳到新顺序。
     * 会话被整条换掉（id 全不匹配）时 `displayed` 本身就等于会话顺序，同样会释放。
     */
    fun onSessionQueueChanged(sessionQueue: List<QueueTrack>) {
        if (isDragging) return
        if (order == null) return
        if (displayed(sessionQueue).map { it.id } == sessionQueue.map { it.id }) order = null
    }

    /** 界面要显示的队列：会话快照 + 暂态顺序。没有暂态时原样返回会话队列。 */
    fun displayed(sessionQueue: List<QueueTrack>): List<QueueTrack> =
        applyQueueOrder(sessionQueue, order)
}

/**
 * 松手后的暂态最长存活时间：播放器一直不接受回写（或队列一直追不上）时，超时放开暂态，
 * 别让面板永久停在一条没生效的顺序上。覆盖至少 3 轮 500ms 轮询。
 */
internal const val QUEUE_DRAG_SETTLE_TIMEOUT_MS = 1_600L
