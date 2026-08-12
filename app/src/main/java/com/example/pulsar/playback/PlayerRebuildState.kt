package com.example.pulsar.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters

/** 播放器替换时需要迁移的不可变播放现场。 */
data class PlayerRebuildState(
    val mediaItems: List<MediaItem>,
    val mediaItemIndex: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val playbackParameters: PlaybackParameters,
) {
    val hasCurrentItem: Boolean
        get() = mediaItemIndex != C.INDEX_UNSET && mediaItems.isNotEmpty()
}

/** 将旧播放器的队列位置规范为可安全恢复到新播放器的值。 */
fun normalizePlayerPosition(
    mediaItemCount: Int,
    mediaItemIndex: Int,
    positionMs: Long,
): Pair<Int, Long> {
    if (mediaItemCount <= 0) return C.INDEX_UNSET to 0L

    val safeIndex = mediaItemIndex.coerceIn(0, mediaItemCount - 1)
    val safePosition = positionMs.takeIf { it >= 0L } ?: 0L
    return safeIndex to safePosition
}
