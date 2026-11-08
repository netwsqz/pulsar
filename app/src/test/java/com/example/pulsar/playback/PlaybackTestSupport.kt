package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.pulsar.domain.model.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/**
 * 手写 Fake remote：记录 session 发出去的每条命令，并允许按命令 action 配置返回值。
 *
 * **为什么不用 Mockito/MockK**：项目约定手写 Fake；这里的断言点是「发了哪条命令、发了几次、
 * 返回值如何影响 state」，手写记录比验证框架更直白。
 *
 * `Bundle` 在 JVM 单测里是桩，参数内容读不出来，所以 Fake 只记录命令本身，不解析 args。
 * 需要验证参数的场景（USB 音量绝对值等）留给真机。
 */
internal class FakePlaybackRemote : PlaybackRemote {

    data class SentCommand(val command: SessionCommand, val args: Bundle)

    data class PendingCommand(
        val command: SessionCommand,
        val future: SettableFuture<RemoteCommandResult>
    )

    val listeners = mutableListOf<RemoteListener>()

    /** 当前快照；测试直接改它来模拟播放器状态变化。 */
    var snapshot = RemoteSnapshot()

    /** 按 `SessionCommand.customAction` 配置的结果；未配置默认成功且无 extras。 */
    val commandResults = mutableMapOf<String, RemoteCommandResult>()

    /** 所有命令是否可用（`isCommandAvailable`）。 */
    var commandsAvailable = true

    /** true = `send` 返回未完成的 future，用于模拟命令在途。 */
    var holdCommands = false

    val sentCommands = mutableListOf<SentCommand>()
    val pendingCommands = mutableListOf<PendingCommand>()

    /** 会话是否调过 [close]。 */
    var closed = false
        private set

    fun sentCount(action: String): Int = sentCommands.count { it.command.customAction == action }

    fun complete(index: Int, result: RemoteCommandResult = resultFor(pendingCommands[index].command)) {
        pendingCommands[index].future.set(result)
    }

    fun resultFor(command: SessionCommand): RemoteCommandResult =
        commandResults[command.customAction] ?: RemoteCommandResult(SessionResult.RESULT_SUCCESS)

    // ── 事件注入 ─────────────────────────────────────────────────────────────
    fun connect() = listeners.toList().forEach { it.onConnected() }

    fun connectionFailed() = listeners.toList().forEach { it.onConnectionFailed() }

    fun unavailable() = listeners.toList().forEach { it.onUnavailable() }

    fun emitIsPlaying(playing: Boolean) = listeners.toList().forEach { it.onIsPlayingChanged(playing) }

    fun emitTransition() = listeners.toList().forEach { it.onMediaItemTransition() }

    fun emitPlaybackState(state: Int) = listeners.toList().forEach { it.onPlaybackStateChanged(state) }

    fun emitError(message: String) = listeners.toList().forEach { it.onPlayerError(message) }

    // ── PlaybackRemote ───────────────────────────────────────────────────────
    override fun play() = Unit

    override fun pause() = Unit

    override fun prepare() = Unit

    override fun setQueue(items: List<MediaItem>, startIndex: Int) = Unit

    override fun setSingleItem(item: MediaItem) = Unit

    override fun insertAfterCurrent(item: MediaItem): Boolean = true

    override fun removeAt(index: Int) = Unit

    override fun move(from: Int, to: Int) = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekToDefaultPosition(index: Int) = Unit

    override fun seekToNext() = Unit

    override fun seekToPrevious() = Unit

    override fun snapshot(): RemoteSnapshot = snapshot

    override fun addListener(listener: RemoteListener) {
        listeners += listener
    }

    override fun removeListener(listener: RemoteListener) {
        listeners -= listener
    }

    override fun close() {
        closed = true
    }

    override fun isCommandAvailable(command: SessionCommand): Boolean = commandsAvailable

    override fun send(command: SessionCommand, args: Bundle): ListenableFuture<RemoteCommandResult> {
        sentCommands += SentCommand(command, args)
        if (holdCommands) {
            val future = SettableFuture.create<RemoteCommandResult>()
            pendingCommands += PendingCommand(command, future)
            return future
        }
        return Futures.immediateFuture(resultFor(command))
    }
}

/** 测试用条目工厂：默认按 track id 造一个最小 MediaItem（不碰 Uri，JVM 上稳定）。 */
internal class FakeTrackMediaItemFactory(
    private val itemFor: (Track) -> MediaItem? = { track ->
        MediaItem.Builder().setMediaId(track.id).build()
    }
) : TrackMediaItemFactory {
    override fun create(track: Track): MediaItem? = itemFor(track)
}

/** 测试用离线守门：默认全部可播，可按 id 配置不可播原因。 */
internal class FakePlaybackGate(
    private val blocked: Map<String, String> = emptyMap()
) : PlaybackGate {
    override fun isPlayable(track: Track): Boolean = track.id !in blocked

    override fun unplayableReason(track: Track): String? = blocked[track.id]
}
