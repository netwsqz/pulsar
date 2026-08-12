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
    const val KEY_ENABLED = "enabled"
    const val KEY_STATE = "state"
    const val KEY_ERROR = "error"
    const val KEY_VOLUME_PERCENT = "volumePercent"

    fun setCommand(): SessionCommand = SessionCommand(SET_ENABLED, Bundle.EMPTY)

    fun setArgs(enabled: Boolean): Bundle = Bundle().apply { putBoolean(KEY_ENABLED, enabled) }
    fun getCommand(): SessionCommand = SessionCommand(GET_STATE, Bundle.EMPTY)

    fun setVolumeCommand(): SessionCommand = SessionCommand(SET_VOLUME, Bundle.EMPTY)
    fun setVolumeArgs(percent: Int): Bundle = Bundle().apply { putInt(KEY_VOLUME_PERCENT, percent) }

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
