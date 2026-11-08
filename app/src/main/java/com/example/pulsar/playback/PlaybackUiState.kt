package com.example.pulsar.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.example.pulsar.QueueTrack
import com.example.pulsar.domain.model.Track

/**
 * USB 独占输出的对外状态。
 *
 * [state] 是播放服务状态机的线上值（`disabled` / `no_device` / `acknowledging_permission` /
 * `ready` / `streaming` / `error`），文案由界面层映射，模块不碰 UI 文案。
 */
internal data class UsbOutputState(
    val enabled: Boolean = false,
    val state: String = "disabled",
    val error: String? = null,
    val pending: Boolean = false,
    val volumePercent: Int = 100
)

/**
 * 播放会话对外的唯一状态面。
 *
 * 迷你条、全屏播放页、设置页读同一份状态；`ControllerState` 只是它在 Step 3 之前的
 * 过渡映射（还带着旧路径直接使用的 `controller`）。
 *
 * `durationMs` 的默认值保持 [C.TIME_UNSET]（负数），与旧实现一致——界面据此判断
 * 「还不知道时长」。
 */
internal data class PlaybackUiState(
    val isConnected: Boolean = false,
    val status: String = "正在连接播放服务…",
    val playbackError: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = C.TIME_UNSET,
    val queue: List<QueueTrack> = emptyList(),
    /** 当前播放模式。Step 3 起由会话持有，界面不再各存一份本地副本。 */
    val mode: PlaybackMode = PlaybackMode.SEQUENCE,
    /**
     * 当前曲目的原始 `MediaItem`。界面用它把「当前曲目」同步回 ViewModel
     * （歌词 / 封面 / 历史都按曲目 id 走），这样 UI 不必再直接持有控制器。
     */
    val currentMediaItem: MediaItem? = null,
    val usb: UsbOutputState = UsbOutputState(),
    val silenceSkipEnabled: Boolean = true
)

/** 播放会话抛给界面的事件（Snackbar / 音量浮窗等一次性反馈）。 */
internal sealed interface PlaybackEvent {
    /** 普通提示文案，走 Snackbar。 */
    data class Message(val text: String) : PlaybackEvent

    /** 曲目不可播（离线未下载等），文案由 gate 给出。 */
    data class Unplayable(val reason: String) : PlaybackEvent

    /**
     * 点歌成功进入播放队列。界面据此把可播列表记进 ViewModel——解出可播列表是会话
     * （gate 在会话内）的事，界面不再自己过滤一遍。
     */
    data class PlaybackQueuePrepared(
        val tracks: List<Track>,
        val startTrackId: String
    ) : PlaybackEvent

    /**
     * USB 音量命令成功后的新音量。界面拿它立刻点亮音量浮窗——等 500ms 轮询会慢半拍。
     */
    data class VolumeApplied(val percent: Int) : PlaybackEvent
}
