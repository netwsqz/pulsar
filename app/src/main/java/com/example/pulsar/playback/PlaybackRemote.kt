package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.pulsar.QueueTrack
import com.google.common.util.concurrent.ListenableFuture

/**
 * 播放服务命令结果的中性表示。
 *
 * 生产侧由 [MediaControllerPlaybackRemote] 从 `SessionResult` 转换而来。**为什么不在
 * session 里直接解析 `Bundle`**：JVM 单测跑在桩化的 android.jar 上，`Bundle.containsKey`
 * 永远返回 false，extras 里的值读不出来。把「Bundle → 普通 Map」的转换压在 adapter 边界，
 * session 只读这个结构，USB / 静音命令的成功与失败才能在单测里覆盖。
 */
internal data class RemoteCommandResult(
    val resultCode: Int,
    val extras: Map<String, Any?> = emptyMap()
) {
    val isSuccess: Boolean get() = resultCode == SessionResult.RESULT_SUCCESS

    fun contains(key: String): Boolean = extras.containsKey(key)

    fun boolean(key: String, default: Boolean = false): Boolean = extras[key] as? Boolean ?: default

    fun int(key: String): Int? = extras[key] as? Int

    fun string(key: String): String? = extras[key] as? String
}

/**
 * 播放服务的一次状态快照。
 *
 * 500ms 轮询一次取回，避免逐个 getter 穿过 Binder；连接状态也放在这里，session/UI
 * 只需要读同一份快照。
 */
internal data class RemoteSnapshot(
    val isConnected: Boolean = false,
    val currentTitle: String? = null,
    val playbackState: Int = Player.STATE_IDLE,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val queue: List<QueueTrack> = emptyList()
)

/**
 * 播放远程的事件面。
 *
 * 连接结果也走这里：remote 在 `addListener` 时会重放当前连接状态，所以晚注册的监听者
 * 不会漏掉「已连接 / 连接失败 / 服务不可用」。
 */
internal interface RemoteListener {
    fun onConnected()

    fun onConnectionFailed()

    fun onUnavailable()

    fun onIsPlayingChanged(isPlaying: Boolean)

    fun onMediaItemTransition()

    fun onPlaybackStateChanged(playbackState: Int)

    /** 播放错误已经映射成用户可读文案（见 [friendlyPlaybackError]）。 */
    fun onPlayerError(message: String)
}

/**
 * 播放远程的 seam：生产实现是 [MediaControllerPlaybackRemote]，测试实现是手写 Fake。
 *
 * 这里只放「session 真正会调用的动作」，不暴露 `MediaController` 本身——session 因此
 * 不依赖 Media3 控制器的生命周期，测试也不需要 Android 运行时。
 *
 * 队列写操作（[setQueue] / [insertAfterCurrent] 等）本轮仍由 UI 旧路径直接操作
 * `MediaController`，接口先把形状留出来，Step 3 再切过来。
 */
internal interface PlaybackRemote {
    fun play()

    fun pause()

    fun prepare()

    fun setQueue(items: List<MediaItem>, startIndex: Int)

    fun setSingleItem(item: MediaItem)

    /** 把条目插到当前曲目之后；remote 未连接时返回 `false`。 */
    fun insertAfterCurrent(item: MediaItem): Boolean

    fun removeAt(index: Int)

    fun move(from: Int, to: Int)

    fun seekTo(positionMs: Long)

    fun seekToDefaultPosition(index: Int)

    fun seekToNext()

    fun seekToPrevious()

    /** 一次性拿到进度、时长、播放状态、队列与连接状态。 */
    fun snapshot(): RemoteSnapshot

    fun addListener(listener: RemoteListener)

    fun removeListener(listener: RemoteListener)

    /** 解除 adapter 自己注册在底层播放器上的监听（会话销毁时调用，避免重复组合累积监听器）。 */
    fun close()

    /** 会话是否提供这条自定义命令。前台音量键据此决定要不要吃掉按键。 */
    fun isCommandAvailable(command: SessionCommand): Boolean

    fun send(command: SessionCommand, args: Bundle): ListenableFuture<RemoteCommandResult>
}
