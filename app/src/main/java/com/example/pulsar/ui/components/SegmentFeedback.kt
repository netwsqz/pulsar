package com.example.pulsar.ui.components

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import kotlin.math.floor

/** 每次真实跨位最多震一次；快速滑过数格也不补放排队的震动。 */
@Composable
internal fun rememberSegmentTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        var lastTick: Long? = null
        {
            val now = SystemClock.uptimeMillis()
            if (lastTick?.let { now - it >= 55L } != false) {
                lastTick = now
                val effect = if (Build.VERSION.SDK_INT >= 34) {
                    HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                }
                // 不传忽略系统偏好的 flag；系统关闭触感时由平台抑制。
                view.performHapticFeedback(effect)
            }
            Unit
        }
    }
}

/** 反馈刻度不参与取值。滑块本身仍连续跟手，只有跨过一格才请求轻振。 */
internal fun feedbackSegment(fraction: Float, segmentCount: Int = 20): Int =
    if (fraction.isFinite()) floor(fraction.coerceIn(0f, 1f) * segmentCount).toInt() else 0
