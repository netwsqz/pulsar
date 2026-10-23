package com.example.pulsar.playback

import android.view.KeyEvent

/**
 * 前台音量键接管：Activity 在前台时把音量键截下来驱动 USB 独占音量。
 *
 * **为什么需要它**（见 docs/usb-volume-key-interception.md §11）：AOSP 通过 MediaSession
 * 把音量键转发给远程音量会话（[RemoteVolumePlayer] 就是为此而挂），但 MIUI 删掉了这段
 * 派发——`setPlaybackToRemote` 的 API 还在、会话也确实报 `volumeType=REMOTE`，音量键却
 * 始终走本地流。前台这条路不依赖任何系统派发逻辑，代价是**只在 Pulsar 位于前台时有效**。
 *
 * 分工：本类只判断"这次按键要不要吃掉"，不做音量算术——它只发"加一档/减一档"，新音量由
 * 播放服务按自己手里的实时值算（见 `PlaybackService.applyUsbVolume`）。UI 侧只有 500ms
 * 轮询来的快照，在 [Sink] 里自己算会把连按的第二下算成同一个目标值而丢档。
 *
 * 线程：快照与下发口由 UI 层的协程写、输入分发线程读，因此都是 `@Volatile`。
 */
class ForegroundVolumeKeys {

    /** 音量变化下发口，由 UI 层安装（转发成会话命令 `ADJUST_VOLUME`）。 */
    fun interface Sink {
        /**
         * 下发一档音量调整。
         *
         * @param step `+1` 加一档、`-1` 减一档。
         * @return `false` 表示当前无法接管（会话没提供这条命令），调用方应把按键放行给系统。
         */
        fun adjust(step: Int): Boolean
    }

    /**
     * UI 层轮询回填的实时状态；`null` = 尚未就绪，一律不接管。
     *
     * [usbAudioActive] 必须是"USB 泵正在输出"，**不是**"独占开关开着"——没插设备时声音
     * 走系统 AudioTrack，接管音量键只会让音量键彻底失灵（真机反馈）。
     */
    data class Snapshot(val usbAudioActive: Boolean)

    @Volatile
    var snapshot: Snapshot? = null

    @Volatile
    var sink: Sink? = null

    /**
     * 上一次 DOWN 是否被吃掉。**UP 必须跟着同一个选择走**：只吃 DOWN 不吃 UP，系统会在 UP
     * 时补一次自己的音量调整；只吃 UP 不吃 DOWN，DOWN 已经落到系统手里了，同样拦不住。
     *
     * 只在输入分发线程读写，不需要 `@Volatile`。
     */
    private var lastDownConsumed = false

    /**
     * 处理一次音量键事件。
     *
     * @return `true` = 事件已被 App 吃掉（系统不再动它自己的音量）；`false` = 放行给系统。
     *
     * 只有 `ACTION_DOWN` 才下发（长按由系统按输入重复率连续派发 DOWN）。
     */
    fun handle(keyCode: Int, action: Int): Boolean {
        val step = stepFor(keyCode) ?: return false
        if (action == KeyEvent.ACTION_UP) return lastDownConsumed
        val consumed = takeOver(step)
        lastDownConsumed = consumed
        return consumed
    }

    private fun takeOver(step: Int): Boolean {
        if (snapshot?.usbAudioActive != true) return false
        val sink = sink ?: return false
        return sink.adjust(step)
    }

    private fun stepFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> 1
        KeyEvent.KEYCODE_VOLUME_DOWN -> -1
        else -> null
    }
}
