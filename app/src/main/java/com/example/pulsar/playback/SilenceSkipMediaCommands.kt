package com.example.pulsar.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult

/**
 * PlaybackService 与界面之间的静音跳曲开关协议。
 *
 * extras 用 `Bundle()` 而非 `Bundle.EMPTY`（JVM 单测里 `Bundle.EMPTY` 为 null，会让
 * `SessionCommand` 构造抛 NPE），两边都等价于空包。
 */
object SilenceSkipMediaCommands {
    const val SET_ENABLED = "com.example.pulsar.SILENCE_SKIP_SET_ENABLED"
    const val GET_STATE = "com.example.pulsar.SILENCE_SKIP_GET_STATE"
    const val KEY_ENABLED = "enabled"
    const val KEY_ERROR = "error"

    fun getCommand(): SessionCommand = SessionCommand(GET_STATE, Bundle())

    fun setArgs(enabled: Boolean): Bundle = Bundle().apply { putBoolean(KEY_ENABLED, enabled) }

    fun stateBundle(enabled: Boolean): Bundle = Bundle().apply { putBoolean(KEY_ENABLED, enabled) }

    fun errorResult(message: String): SessionResult =
        SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE, Bundle().apply { putString(KEY_ERROR, message) })
}
