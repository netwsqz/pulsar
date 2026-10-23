package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.pulsar.playback.usb.UsbExclusiveState

/** PlaybackService 与界面之间的 USB 独占控制协议。 */
object UsbExclusiveMediaCommands {
    const val SET_ENABLED = "com.example.pulsar.USB_EXCLUSIVE_SET_ENABLED"
    const val GET_STATE = "com.example.pulsar.USB_EXCLUSIVE_GET_STATE"
    const val SET_VOLUME = "com.example.pulsar.USB_EXCLUSIVE_SET_VOLUME"
    const val ADJUST_VOLUME = "com.example.pulsar.USB_EXCLUSIVE_ADJUST_VOLUME"
    const val KEY_ENABLED = "enabled"
    const val KEY_STATE = "state"
    const val KEY_ERROR = "error"
    const val KEY_VOLUME_PERCENT = "volumePercent"
    const val KEY_VOLUME_STEP = "volumeStep"

    fun setCommand(): SessionCommand = SessionCommand(SET_ENABLED, Bundle.EMPTY)

    fun setArgs(enabled: Boolean): Bundle = Bundle().apply { putBoolean(KEY_ENABLED, enabled) }
    fun getCommand(): SessionCommand = SessionCommand(GET_STATE, Bundle.EMPTY)

    fun setVolumeCommand(): SessionCommand = SessionCommand(SET_VOLUME, Bundle.EMPTY)
    fun setVolumeArgs(percent: Int): Bundle = Bundle().apply { putInt(KEY_VOLUME_PERCENT, percent) }

    /**
     * 相对音量：[SET_VOLUME] 传的是绝对值，需要调用方知道当前音量；前台音量键那条路只有
     * 500ms 轮询来的快照，自己算会丢档，所以改传"加一档/减一档"，由服务按自己的实时音量
     * 算（[UsbExclusiveMediaCommands.KEY_VOLUME_STEP]，取值 ±1）。
     */
    fun adjustVolumeCommand(): SessionCommand = SessionCommand(ADJUST_VOLUME, Bundle.EMPTY)

    fun adjustVolumeArgs(step: Int): Bundle = Bundle().apply { putInt(KEY_VOLUME_STEP, step) }

    /**
     * [ADJUST_VOLUME] 成功时随结果带回的新音量（已夹紧）。服务把算完的值顺手还给界面，
     * 界面才能立刻弹音量浮窗——UI 侧只有 500ms 轮询来的快照，等轮询回来浮窗会慢半拍。
     */
    fun volumeResultArgs(percent: Int): Bundle = Bundle().apply { putInt(KEY_VOLUME_PERCENT, percent) }

    /** 从命令结果里读回音量；结果不含该字段时返回 `null`（调用方沿用旧值，不显示浮窗）。 */
    fun volumePercentFrom(extras: Bundle): Int? =
        if (extras.containsKey(KEY_VOLUME_PERCENT)) extras.getInt(KEY_VOLUME_PERCENT) else null

    /**
     * USB 泵是否真的在输出（= 声音已绕过系统混音器）。
     *
     * 音量键接管必须以此为条件，不能用"独占开关开着"：开关开着但没插设备（或泵还没起来）
     * 时声音走的是系统 AudioTrack，此时吃掉音量键只会去改一个对实际出声无效的 USB 音量，
     * 表现为"音量键完全没反应"。只有泵在跑，系统音量才是那个无效的东西。
     */
    fun isUsbAudioActive(state: String): Boolean = state == "streaming"

    /** 同上，给服务侧用（状态机对象，避免为一次判断绕一圈字符串）。 */
    fun isUsbAudioActive(state: UsbExclusiveState): Boolean = state is UsbExclusiveState.Streaming

    fun stateBundle(
        enabled: Boolean,
        state: UsbExclusiveState,
        volumePercent: Int = 100,
    ): Bundle = Bundle().apply {
        putBoolean(KEY_ENABLED, enabled)
        putString(KEY_STATE, state.toWireValue())
        putInt(KEY_VOLUME_PERCENT, volumePercent)
        if (state is UsbExclusiveState.Error) putString(KEY_ERROR, state.reason)
    }

    fun errorResult(message: String): SessionResult =
        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, Bundle().apply { putString(KEY_ERROR, message) })

    private fun UsbExclusiveState.toWireValue(): String = when (this) {
        UsbExclusiveState.Disabled -> "disabled"
        UsbExclusiveState.NoDevice -> "no_device"
        UsbExclusiveState.AwaitingPermission -> "awaiting_permission"
        UsbExclusiveState.Ready -> "ready"
        UsbExclusiveState.Streaming -> "streaming"
        is UsbExclusiveState.Error -> "error"
    }
}
