package com.example.pulsar.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.MotionDurationScale

internal val PageEnterEasing = CubicBezierEasing(0.2f, 0.65f, 0.3f, 1f)
internal val PageExitEasing = CubicBezierEasing(0.4f, 0f, 0.8f, 0.6f)

/** Compose 自动应用这个倍率；图片淡变和按压形变不是同一条动画链，显式读取即可。 */
@Composable
internal fun systemMotionScale(): Float =
    rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
