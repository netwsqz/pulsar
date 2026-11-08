package com.example.pulsar.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import com.example.pulsar.QueueTrack
import com.example.pulsar.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 曲目 → 播放条目。返回 `null` = 算不出流地址（未登录 / 无曲库数据）。
 *
 * Step 2 只把它挂进构造签名，Step 3 的 `Play` 意图才真正使用。
 */
internal fun interface TrackMediaItemFactory {
    fun create(track: Track): MediaItem?
}

/**
 * 离线守门：哪些曲目现在能播、不能播时给用户什么话术。
 *
 * 生产实现只是 `JellyfinViewModel::isPlayable` / `::unplayableReason` 两个方法引用；
 * 放成接口是为了让 session 的单测不依赖 ViewModel。
 */
internal interface PlaybackGate {
    fun isPlayable(track: Track): Boolean

    fun unplayableReason(track: Track): String?
}

/**
 * 播放会话的意图：界面只声明「用户想做什么」，所有播放器写操作由会话执行。
 */
internal sealed interface PlaybackIntent {
    /** 点歌播放：从 [startTrackId] 开始，队列顺序由会话旋转。 */
    data class Play(val tracks: List<Track>, val startTrackId: String) : PlaybackIntent

    /** 打开手机里的本地音频（SAF `content://`）：单曲 set + prepare + play。 */
    data class PlayLocalFile(val uri: Uri) : PlaybackIntent

    /** 点播放队列里的某一行：跳到该索引并开始播放。 */
    data class PlayQueueItem(val index: Int) : PlaybackIntent

    /** 添加到播放队列：插在当前曲目之后；队列为空时直接开始播放。 */
    data class AddToQueue(val track: Track) : PlaybackIntent

    /** 从队列中移除指定 id 的曲目（跳过当前项，倒序摘）。 */
    data class RemoveFromQueue(val ids: Set<String>) : PlaybackIntent

    /** 拖拽重排后的目标顺序。 */
    data class Reorder(val ordered: List<QueueTrack>) : PlaybackIntent

    data class SeekTo(val positionMs: Long) : PlaybackIntent

    data object Next : PlaybackIntent

    data object Previous : PlaybackIntent

    data object TogglePlayPause : PlaybackIntent

    data class SetMode(val mode: PlaybackMode) : PlaybackIntent

    /** 顺序循环 → 单曲循环 → 随机播放。 */
    data object CycleMode : PlaybackIntent

    data class SetUsbExclusive(val enabled: Boolean) : PlaybackIntent

    data class SetUsbVolume(val percent: Int) : PlaybackIntent

    data class SetSilenceSkip(val enabled: Boolean) : PlaybackIntent

    /** USB 音量相对调整一档（`+1` / `-1`），算术留在服务里，UI 不自己算百分比。 */
    data class AdjustVolume(val step: Int) : PlaybackIntent
}

/** 进度/队列/USB/静音状态的轮询周期（与重构前的 500ms 完全一致）。 */
internal const val PLAYBACK_POLL_INTERVAL_MS = 500L

private const val STATUS_CONNECTION_FAILED = "播放服务连接失败"
private const val STATUS_UNAVAILABLE = "播放服务不可用"
private const val STATUS_USB_SET_FAILED = "USB 独占设置失败"
private const val STATUS_USB_VOLUME_FAILED = "USB 音量设置失败"

/**
 * 播放会话：连接播放服务、监听播放器事件、500ms 轮询进度/队列/USB/静音，并下发
 * USB 独占 / USB 音量 / 静音跳过的自定义命令。
 *
 * 界面只做两件事：读 [state]，发 [dispatch]。所有状态写入都走本类的 [scope]（生产是
 * Main dispatcher），适配器只做转发，UI 不直接碰控制器。
 *
 * **前置状态机**：连接成功（[RemoteListener.onConnected]）才开始轮询；[close] 后
 * 轮询停止、命令一律拒绝。两种失败（连接失败 / 服务不可用）映射成不同文案，不混淆。
 */
internal class PlaybackSession(
    private val remote: PlaybackRemote,
    private val mediaItems: TrackMediaItemFactory,
    private val gate: PlaybackGate,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PlaybackEvent> = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var usbQueryInFlight = false
    private var silenceQueryInFlight = false

    @Volatile
    private var closed = false

    private val listener = object : RemoteListener {
        override fun onConnected() {
            if (closed) return
            val snapshot = remote.snapshot()
            _state.update {
                it.copy(
                    isConnected = true,
                    isPlaying = snapshot.isPlaying,
                    currentMediaItem = snapshot.currentMediaItem,
                    status = playbackStatusText(snapshot, it.playbackError)
                )
            }
            startPolling()
        }

        override fun onConnectionFailed() {
            if (closed) return
            _state.update { it.copy(isConnected = false, status = STATUS_CONNECTION_FAILED) }
        }

        override fun onUnavailable() {
            if (closed) return
            _state.update { it.copy(isConnected = false, status = STATUS_UNAVAILABLE) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (closed) return
            val snapshot = remote.snapshot()
            _state.update { current ->
                val error = if (isPlaying) null else current.playbackError
                current.copy(
                    isPlaying = isPlaying,
                    playbackError = error,
                    status = playbackStatusText(snapshot, error)
                )
            }
        }

        override fun onMediaItemTransition() {
            if (closed) return
            val snapshot = remote.snapshot()
            _state.update { current ->
                current.copy(
                    playbackError = null,
                    status = playbackStatusText(snapshot, null),
                    // 队列快照同步刷新：播放键的可用性与队列面板不能等下一轮 500ms 轮询。
                    queue = snapshot.queue,
                    currentMediaItem = snapshot.currentMediaItem
                )
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (closed) return
            val snapshot = remote.snapshot()
            _state.update { current ->
                val error = if (playbackState == Player.STATE_BUFFERING) null else current.playbackError
                current.copy(playbackError = error, status = playbackStatusText(snapshot, error))
            }
        }

        override fun onPlayerError(message: String) {
            if (closed) return
            _state.update { it.copy(playbackError = message, status = message) }
        }
    }

    init {
        remote.addListener(listener)
    }

    /** 唯一入口。返回 `false` = 意图没有生效（会话已关闭 / 未连接 / 命令不可用）。 */
    fun dispatch(intent: PlaybackIntent): Boolean {
        if (closed) return false
        return when (intent) {
            is PlaybackIntent.Play -> play(intent.tracks, intent.startTrackId)
            is PlaybackIntent.PlayLocalFile -> playLocalFile(intent.uri)
            is PlaybackIntent.PlayQueueItem -> playQueueItem(intent.index)
            is PlaybackIntent.AddToQueue -> addToQueue(intent.track)
            is PlaybackIntent.RemoveFromQueue -> removeFromQueue(intent.ids)
            is PlaybackIntent.Reorder -> reorder(intent.ordered)
            is PlaybackIntent.SeekTo -> seekTo(intent.positionMs)
            PlaybackIntent.Next -> skipToNext()
            PlaybackIntent.Previous -> skipToPrevious()
            PlaybackIntent.TogglePlayPause -> togglePlayPause()
            is PlaybackIntent.SetMode -> setMode(intent.mode)
            PlaybackIntent.CycleMode -> cycleMode()
            is PlaybackIntent.SetUsbExclusive -> setUsbExclusive(intent.enabled)
            is PlaybackIntent.SetUsbVolume -> setUsbVolume(intent.percent)
            is PlaybackIntent.SetSilenceSkip -> setSilenceSkip(intent.enabled)
            is PlaybackIntent.AdjustVolume -> adjustVolume(intent.step)
        }
    }

    /** 停止轮询并解除监听。之后 [dispatch] 一律返回 `false`。 */
    fun close() {
        if (closed) return
        closed = true
        pollJob?.cancel()
        pollJob = null
        remote.removeListener(listener)
        remote.close()
    }

    private fun startPolling() {
        if (closed) return
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && !closed) {
                poll()
                delay(PLAYBACK_POLL_INTERVAL_MS)
            }
        }
    }

    /** 一轮轮询：进度 / 时长 / 播放中 / 队列，外加 USB 与静音的查询（各自有在途守卫）。 */
    private fun poll() {
        if (closed) return
        val snapshot = remote.snapshot()
        _state.update {
            it.copy(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                isPlaying = snapshot.isPlaying,
                queue = snapshot.queue,
                currentMediaItem = snapshot.currentMediaItem
            )
        }
        queryUsbState()
        querySilenceSkip()
    }

    private fun queryUsbState() {
        if (usbQueryInFlight) return
        usbQueryInFlight = true
        val future = runCatching { remote.send(UsbExclusiveMediaCommands.getCommand(), Bundle()) }
            .getOrElse {
                usbQueryInFlight = false
                return
            }
        future.addListener({
            if (!closed) {
                runCatching {
                    val result = future.get()
                    if (result.isSuccess && result.contains(UsbExclusiveMediaCommands.KEY_STATE)) {
                        _state.update { current ->
                            val volume = if (result.contains(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT)) {
                                result.int(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT) ?: current.usb.volumePercent
                            } else {
                                current.usb.volumePercent
                            }
                            current.copy(
                                usb = current.usb.copy(
                                    enabled = result.boolean(UsbExclusiveMediaCommands.KEY_ENABLED),
                                    state = result.string(UsbExclusiveMediaCommands.KEY_STATE).orEmpty(),
                                    error = result.string(UsbExclusiveMediaCommands.KEY_ERROR),
                                    volumePercent = volume
                                )
                            )
                        }
                    }
                }
            }
            usbQueryInFlight = false
        }, { runnable -> runnable.run() })
    }

    private fun querySilenceSkip() {
        if (silenceQueryInFlight) return
        silenceQueryInFlight = true
        val future = runCatching { remote.send(SilenceSkipMediaCommands.getCommand(), Bundle()) }
            .getOrElse {
                silenceQueryInFlight = false
                return
            }
        future.addListener({
            if (!closed) {
                runCatching {
                    val result = future.get()
                    if (result.isSuccess && result.contains(SilenceSkipMediaCommands.KEY_ENABLED)) {
                        _state.update {
                            it.copy(
                                silenceSkipEnabled = result.boolean(
                                    SilenceSkipMediaCommands.KEY_ENABLED,
                                    it.silenceSkipEnabled
                                )
                            )
                        }
                    }
                }
            }
            silenceQueryInFlight = false
        }, { runnable -> runnable.run() })
    }

    // ── 播放 / 队列意图 ─────────────────────────────────────────────────────

    /**
     * 点歌播放。不可播的曲目从队列里筛掉；**点的那首不可播 → 只发 [PlaybackEvent.Unplayable]，
     * 不写队列**（旧界面就是这个口径，避免「正在播」镜像指向一首根本没在放的歌）。
     *
     * 队列顺序仍用 [buildPlaybackOrder] 旋转到点的那首开头；某首算不出播放条目时整条放弃
     * （保留旧 `playTracks` 语义，不在这里做 null URL 容错）。
     */
    private fun play(tracks: List<Track>, startTrackId: String): Boolean {
        if (!remote.snapshot().isConnected) return false
        val start = tracks.firstOrNull { it.id == startTrackId }
        val reason = start?.let(gate::unplayableReason)
        if (reason != null) {
            _events.tryEmit(PlaybackEvent.Unplayable(reason))
            return true
        }
        val playable = tracks.filter(gate::isPlayable)
        // ViewModel 的可播队列记录保持旧顺序：先记队列，再尝试起播（起播失败也不回滚记录）。
        _events.tryEmit(PlaybackEvent.PlaybackQueuePrepared(playable, startTrackId))
        val order = buildPlaybackOrder(playable, startTrackId) ?: return false
        val items = order.map { track -> mediaItems.create(track) ?: return false }
        remote.applyPlaybackMode(state.value.mode)
        remote.setQueue(items, 0)
        remote.prepare()
        remote.play()
        return true
    }

    private fun playLocalFile(uri: Uri): Boolean {
        if (!remote.snapshot().isConnected) return false
        remote.setSingleItem(MediaItem.fromUri(uri))
        remote.prepare()
        remote.play()
        return true
    }

    private fun playQueueItem(index: Int): Boolean {
        if (!remote.snapshot().isConnected) return false
        remote.seekToDefaultPosition(index)
        remote.play()
        return true
    }

    /** 添加到队列：插在当前曲目之后；队列为空时 set + prepare + play。 */
    private fun addToQueue(track: Track): Boolean {
        if (!remote.snapshot().isConnected) return false
        val reason = gate.unplayableReason(track)
        if (reason != null) {
            _events.tryEmit(PlaybackEvent.Unplayable(reason))
            return true
        }
        val item = mediaItems.create(track) ?: return false
        if (remote.snapshot().queue.isEmpty()) {
            remote.setSingleItem(item)
            remote.prepare()
            remote.play()
        } else {
            remote.insertAfterCurrent(item)
        }
        _events.tryEmit(PlaybackEvent.Message("已添加到播放队列"))
        return true
    }

    /** 与旧 `removeFromQueue` 一致：倒序摘、跳过当前项。 */
    private fun removeFromQueue(ids: Set<String>): Boolean {
        if (!remote.snapshot().isConnected) return false
        val snapshot = remote.snapshot()
        val currentIndex = snapshot.queue.indexOfFirst { it.isCurrent }
        removeIdsFromQueue(
            mediaIds = snapshot.queue.map { it.id },
            ids = ids,
            currentIndex = currentIndex
        ).forEach(remote::removeAt)
        return true
    }

    /** 与旧 `applyQueueOrder` 一致：逐条 move，位置序列由 [applyOrderToPositions] 模拟。 */
    private fun reorder(ordered: List<QueueTrack>): Boolean {
        if (!remote.snapshot().isConnected) return false
        val currentIds = remote.snapshot().queue.map { it.id }
        applyOrderToPositions(currentIds, ordered.map { it.id }).forEach { (from, to) ->
            remote.move(from, to)
        }
        return true
    }

    private fun seekTo(positionMs: Long): Boolean {
        if (!remote.snapshot().isConnected) return false
        remote.seekTo(positionMs)
        return true
    }

    private fun skipToNext(): Boolean {
        if (!remote.snapshot().isConnected) return false
        remote.seekToNext()
        return true
    }

    private fun skipToPrevious(): Boolean {
        if (!remote.snapshot().isConnected) return false
        remote.seekToPrevious()
        return true
    }

    private fun togglePlayPause(): Boolean {
        val snapshot = remote.snapshot()
        if (!snapshot.isConnected) return false
        if (snapshot.isPlaying) remote.pause() else remote.play()
        return true
    }

    /** 播放模式写入会话状态并应用到播放器；未连接时也更新状态（与旧本地副本一致）。 */
    private fun setMode(mode: PlaybackMode): Boolean {
        _state.update { it.copy(mode = mode) }
        remote.applyPlaybackMode(mode)
        return true
    }

    private fun cycleMode(): Boolean = setMode(state.value.mode.next())

    private fun setUsbExclusive(enabled: Boolean): Boolean {
        if (!remote.snapshot().isConnected) return false
        _state.update { it.copy(usb = it.usb.copy(pending = true)) }
        val setFuture = runCatching {
            remote.send(
                SessionCommand(UsbExclusiveMediaCommands.SET_ENABLED, Bundle()),
                UsbExclusiveMediaCommands.setArgs(enabled)
            )
        }.getOrElse {
            _state.update { it.copy(usb = it.usb.copy(error = STATUS_USB_SET_FAILED, pending = false)) }
            return false
        }
        setFuture.addListener({
            if (closed) return@addListener
            runCatching {
                // 设置命令的结果码不检查（与旧实现一致），靠回读 GET_STATE 确认最终状态。
                val getFuture = remote.send(UsbExclusiveMediaCommands.getCommand(), Bundle())
                getFuture.addListener({
                    if (closed) return@addListener
                    runCatching {
                        val result = getFuture.get()
                        if (!result.isSuccess) throw IllegalStateException("USB 独占设置命令失败")
                        _state.update { current ->
                            current.copy(
                                usb = current.usb.copy(
                                    enabled = result.boolean(UsbExclusiveMediaCommands.KEY_ENABLED),
                                    state = result.string(UsbExclusiveMediaCommands.KEY_STATE).orEmpty(),
                                    error = result.string(UsbExclusiveMediaCommands.KEY_ERROR)
                                )
                            )
                        }
                    }.onFailure {
                        _state.update { current ->
                            current.copy(usb = current.usb.copy(error = STATUS_USB_SET_FAILED))
                        }
                    }
                    _state.update { current -> current.copy(usb = current.usb.copy(pending = false)) }
                }, { runnable -> runnable.run() })
            }.onFailure {
                _state.update { current ->
                    current.copy(usb = current.usb.copy(error = STATUS_USB_SET_FAILED, pending = false))
                }
            }
        }, { runnable -> runnable.run() })
        return true
    }

    private fun setUsbVolume(percent: Int): Boolean {
        // 本地先生效：滑块松手后数字立刻跳到位，不等命令往返。
        _state.update { it.copy(usb = it.usb.copy(volumePercent = percent)) }
        if (!remote.snapshot().isConnected) return false
        return runCatching {
            val future = remote.send(
                UsbExclusiveMediaCommands.setVolumeCommand(),
                UsbExclusiveMediaCommands.setVolumeArgs(percent)
            )
            future.addListener({
                if (closed) return@addListener
                runCatching {
                    if (!future.get().isSuccess) {
                        _state.update { current ->
                            current.copy(usb = current.usb.copy(error = STATUS_USB_VOLUME_FAILED))
                        }
                    }
                }
            }, { runnable -> runnable.run() })
        }.isSuccess
    }

    private fun setSilenceSkip(enabled: Boolean): Boolean {
        // 本地先生效，开关不卡顿。
        _state.update { it.copy(silenceSkipEnabled = enabled) }
        if (!remote.snapshot().isConnected) return false
        return runCatching {
            // 不在主线程同步 .get()：服务里 SET_ENABLED 要靠主线程协程写完 DataStore 才
            // set 这个 future，同步等会和那个协程互相卡死（与旧实现同一处 ANR 注释）。
            remote.send(
                SessionCommand(SilenceSkipMediaCommands.SET_ENABLED, Bundle()),
                SilenceSkipMediaCommands.setArgs(enabled)
            ).addListener({ }, { runnable -> runnable.run() })
        }.isSuccess
    }

    private fun adjustVolume(step: Int): Boolean {
        val command = UsbExclusiveMediaCommands.adjustVolumeCommand()
        if (!remote.isCommandAvailable(command)) return false
        return runCatching {
            val future = remote.send(command, UsbExclusiveMediaCommands.adjustVolumeArgs(step))
            future.addListener({
                if (closed) return@addListener
                runCatching {
                    val result = future.get()
                    if (result.isSuccess) {
                        result.int(UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT)
                            ?.let { _events.tryEmit(PlaybackEvent.VolumeApplied(it)) }
                    }
                }
            }, { runnable -> runnable.run() })
        }.isSuccess
    }
}

/**
 * 状态文案：错误优先，其次连接，最后「曲目 · 播放态」。
 *
 * 与重构前的 `controllerStatus` 逐字一致（含 `未选择媒体` 兜底）。
 */
internal fun playbackStatusText(snapshot: RemoteSnapshot, playbackError: String?): String {
    if (playbackError != null) return playbackError
    if (!snapshot.isConnected) return STATUS_UNAVAILABLE
    val title = snapshot.currentTitle ?: "未选择媒体"
    val state = when {
        snapshot.playbackState == Player.STATE_BUFFERING -> "缓冲中"
        snapshot.playbackState == Player.STATE_ENDED -> "已结束"
        snapshot.isPlaying -> "播放中"
        else -> "已暂停"
    }
    return "$title · $state"
}
