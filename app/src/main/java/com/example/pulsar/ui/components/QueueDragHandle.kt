package com.example.pulsar.ui.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.example.pulsar.ui.theme.systemMotionScale
import sh.calvin.reorderable.ReorderableCollectionItemScope

/** Reorderable 的停止回调也会在取消时触发；只有真正松手的 Stop 才能写回会话。 */
@Composable
internal fun ReorderableCollectionItemScope.queueDragHandle(
    enabled: Boolean,
    onStart: () -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    // 不用 Compose 状态：标记只由手势和收尾更新，不参与绘制，也不会因标记变化重启手势。
    val gesture = remember { QueueHandleGesture() }
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    LaunchedEffect(source) {
        source.interactions.collect { event ->
            if (gesture.started && (event is DragInteraction.Stop || event is DragInteraction.Cancel)) {
                gesture.started = false
                if (event is DragInteraction.Stop) drop() else cancel()
            }
        }
    }
    DisposableEffect(enabled) {
        onDispose {
            if (gesture.started) {
                gesture.started = false
                cancel()
            }
        }
    }
    return Modifier.longPressDraggableHandle(
        enabled = enabled,
        interactionSource = source,
        onDragStarted = {
            gesture.started = true
            onStart()
        }
    )
}

private class QueueHandleGesture(var started: Boolean = false)

/** 每行只做位置过渡；新增/删除和拖动中的行不叠加淡入淡出。 */
@Composable
internal fun LazyItemScope.queuePlacement(): Modifier {
    val scale = systemMotionScale()
    if (scale == 0f) return Modifier
    // LazyList 的位移动画跑在列表自己的协程，不额外写一套排序状态。
    return Modifier.animateItem(fadeInSpec = null, placementSpec = tween((170f * scale).toInt()), fadeOutSpec = null)
}
