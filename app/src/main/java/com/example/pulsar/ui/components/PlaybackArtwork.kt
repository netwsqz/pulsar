package com.example.pulsar.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.example.pulsar.CoverImageFrame
import com.example.pulsar.ui.theme.THEME_TRANSITION_MS

/** 迷你条与全屏页用同一时刻的封面淡变，快速往返不会重新开始载图。 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun PlaybackCover(
    artwork: Transition<Bitmap?>,
    title: String?,
    modifier: Modifier,
    cornerRadius: Dp
) {
    CoverImageFrame(
        modifier, cornerRadius,
        hasArtwork = artwork.currentState != null || artwork.targetState != null
    ) {
        artwork.Crossfade(animationSpec = tween(THEME_TRANSITION_MS)) { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = title.takeIf { bitmap === artwork.targetState },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
