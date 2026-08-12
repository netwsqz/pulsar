package com.example.pulsar.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRebuildStateTest {
    @Test
    fun `empty queue has no current item`() {
        val position = normalizePlayerPosition(0, C.INDEX_UNSET, 12_000L)

        assertEquals(C.INDEX_UNSET, position.first)
        assertEquals(0L, position.second)
    }

    @Test
    fun `index is clamped into queue`() {
        assertEquals(0, normalizePlayerPosition(3, -1, 100L).first)
        assertEquals(2, normalizePlayerPosition(3, 9, 100L).first)
    }

    @Test
    fun `negative position is reset`() {
        assertEquals(0L, normalizePlayerPosition(2, 1, -1L).second)
    }

    @Test
    fun `state reports whether current item can be restored`() {
        val empty = PlayerRebuildState(
            emptyList(), C.INDEX_UNSET, 0L, false, 0, false,
            androidx.media3.common.PlaybackParameters.DEFAULT,
        )
        val populated = empty.copy(
            mediaItemIndex = 0,
            mediaItems = listOf(MediaItem.Builder().setMediaId("track").build()),
        )

        assertFalse(empty.hasCurrentItem)
        assertTrue(populated.hasCurrentItem)
    }
}
