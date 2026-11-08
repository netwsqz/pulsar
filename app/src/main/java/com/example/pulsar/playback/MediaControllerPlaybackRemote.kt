package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.pulsar.QueueTrack
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PlaybackRemote] 的生产适配器：把 `MediaController` 包成会话真正需要的动作面。
 *
 * **连接生命周期在这里**：构造时把 `controllerFuture` 的解析结果翻成
 * [RemoteListener.onConnected] / [RemoteListener.onConnectionFailed]；future 为 null
 * 直接是 [RemoteListener.onUnavailable]（`Bundle.EMPTY` 那类 JVM 桩陷阱不涉及这里，
 * 它只在设备上跑）。晚注册的监听者在 [addListener] 时会收到当前连接状态的重放，
 * 所以 [PlaybackSession] 的初始化监听不会漏掉连接结果。
 *
 * [controller] 是给 Step 3 之前的旧路径（队列写操作仍直接操作控制器）用的过渡出口；
 * 一旦队列意图切进会话，这个属性就该删掉。
 */
internal class MediaControllerPlaybackRemote(
    controllerFuture: ListenableFuture<MediaController>?
) : PlaybackRemote {

    private enum class Connection { Connecting, Connected, Failed, Unavailable }

    @Volatile
    var controller: MediaController? = null
        private set

    @Volatile
    private var connection = if (controllerFuture == null) Connection.Unavailable else Connection.Connecting

    private val listeners = CopyOnWriteArrayList<RemoteListener>()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            notify { it.onIsPlayingChanged(isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            notify { it.onMediaItemTransition() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            notify { it.onPlaybackStateChanged(playbackState) }
        }

        override fun onPlayerError(error: PlaybackException) {
            notify { it.onPlayerError(friendlyPlaybackError(error)) }
        }
    }

    init {
        if (controllerFuture != null) {
            controllerFuture.addListener({
                runCatching { controllerFuture.get() }
                    .onSuccess { resolved ->
                        controller = resolved
                        connection = Connection.Connected
                        resolved.addListener(playerListener)
                        notify { it.onConnected() }
                    }
                    .onFailure {
                        connection = Connection.Failed
                        notify { it.onConnectionFailed() }
                    }
            }, { runnable -> runnable.run() })
        }
    }

    private inline fun notify(crossinline block: (RemoteListener) -> Unit) {
        listeners.forEach { block(it) }
    }

    override fun addListener(listener: RemoteListener) {
        listeners += listener
        // 重放当前连接状态：会话的监听是在构造之后才注册的。
        when (connection) {
            Connection.Connected -> listener.onConnected()
            Connection.Failed -> listener.onConnectionFailed()
            Connection.Unavailable -> listener.onUnavailable()
            Connection.Connecting -> Unit
        }
    }

    override fun removeListener(listener: RemoteListener) {
        listeners -= listener
    }

    override fun close() {
        controller?.removeListener(playerListener)
    }

    override fun snapshot(): RemoteSnapshot {
        val current = controller ?: return RemoteSnapshot(isConnected = false)
        return RemoteSnapshot(
            isConnected = true,
            currentTitle = current.currentMediaItem?.mediaMetadata?.title?.toString(),
            playbackState = current.playbackState,
            isPlaying = current.isPlaying,
            positionMs = current.currentPosition,
            durationMs = current.duration,
            queue = current.queueSnapshot()
        )
    }

    override fun isCommandAvailable(command: SessionCommand): Boolean =
        controller?.isSessionCommandAvailable(command) == true

    override fun send(command: SessionCommand, args: Bundle): ListenableFuture<RemoteCommandResult> {
        val current = controller
            ?: return Futures.immediateFailedFuture(IllegalStateException("播放服务未连接"))
        val future = current.sendCustomCommand(command, args)
        val result = SettableFuture.create<RemoteCommandResult>()
        future.addListener({
            runCatching { result.set(future.get().toRemoteCommandResult()) }
                .onFailure { result.setException(it) }
        }, { runnable -> runnable.run() })
        return result
    }

    override fun play() {
        controller?.play()
    }

    override fun pause() {
        controller?.pause()
    }

    override fun prepare() {
        controller?.prepare()
    }

    override fun setQueue(items: List<MediaItem>, startIndex: Int) {
        controller?.setMediaItems(items, startIndex, 0L)
    }

    override fun setSingleItem(item: MediaItem) {
        controller?.setMediaItem(item)
    }

    override fun insertAfterCurrent(item: MediaItem): Boolean {
        val current = controller ?: return false
        current.addMediaItem(insertAfterCurrentIndex(current.currentMediaItemIndex, current.mediaItemCount), item)
        return true
    }

    override fun removeAt(index: Int) {
        controller?.removeMediaItem(index)
    }

    override fun move(from: Int, to: Int) {
        controller?.moveMediaItem(from, to)
    }

    override fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    override fun seekToDefaultPosition(index: Int) {
        controller?.seekToDefaultPosition(index)
    }

    override fun seekToNext() {
        controller?.seekToNext()
    }

    override fun seekToPrevious() {
        controller?.seekToPrevious()
    }
}

/** 读取播放器当前真实队列的快照（含当前播放项标记）。 */
private fun MediaController.queueSnapshot(): List<QueueTrack> {
    val count = mediaItemCount
    val currentIndex = currentMediaItemIndex
    if (count <= 0) return emptyList()
    return List(count) { index ->
        val item = getMediaItemAt(index)
        val metadata = item.mediaMetadata
        QueueTrack(
            id = item.mediaId.orEmpty(),
            title = metadata.title?.toString() ?: "未知歌曲",
            artist = metadata.artist?.toString() ?: "未知歌手",
            album = metadata.albumTitle?.toString().orEmpty(),
            imageTag = item.localConfiguration?.tag as? String,
            isCurrent = index == currentIndex
        )
    }
}

/** 命令结果从 `SessionResult` 转成中性结构；只搬会话真正读的几个键。 */
private val COMMAND_RESULT_KEYS = listOf(
    UsbExclusiveMediaCommands.KEY_ENABLED, // 与 SilenceSkipMediaCommands.KEY_ENABLED 同值
    UsbExclusiveMediaCommands.KEY_STATE,
    UsbExclusiveMediaCommands.KEY_ERROR,
    UsbExclusiveMediaCommands.KEY_VOLUME_PERCENT
)

private fun SessionResult.toRemoteCommandResult(): RemoteCommandResult {
    val source = extras
    val values = LinkedHashMap<String, Any?>()
    for (key in COMMAND_RESULT_KEYS) {
        if (source.containsKey(key)) {
            @Suppress("DEPRECATION")
            values[key] = source.get(key)
        }
    }
    return RemoteCommandResult(resultCode, values)
}

/** 播放错误 → 用户可读文案（与重构前的 `friendlyPlaybackError` 逐字一致）。 */
internal fun friendlyPlaybackError(error: PlaybackException): String = when {
    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "播放失败：网络连接中断"
    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED -> "播放失败：音频格式不受支持"
    else -> "播放失败：请检查服务器或网络"
}
