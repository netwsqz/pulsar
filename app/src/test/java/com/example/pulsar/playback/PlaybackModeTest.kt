package com.example.pulsar.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeTest {

    @Test
    fun `mode cycles sequence to single repeat to shuffle and back`() {
        assertEquals(PlaybackMode.REPEAT_ONE, PlaybackMode.SEQUENCE.next())
        assertEquals(PlaybackMode.SHUFFLE, PlaybackMode.REPEAT_ONE.next())
        assertEquals(PlaybackMode.SEQUENCE, PlaybackMode.SHUFFLE.next())
    }

    @Test
    fun `sequence mode repeats all without shuffle`() {
        val config = playbackModeConfig(PlaybackMode.SEQUENCE)

        assertEquals(Player.REPEAT_MODE_ALL, config.repeatMode)
        assertEquals(false, config.shuffleEnabled)
    }

    @Test
    fun `single repeat mode repeats one without shuffle`() {
        val config = playbackModeConfig(PlaybackMode.REPEAT_ONE)

        assertEquals(Player.REPEAT_MODE_ONE, config.repeatMode)
        assertEquals(false, config.shuffleEnabled)
    }

    @Test
    fun `shuffle mode repeats all with shuffle`() {
        val config = playbackModeConfig(PlaybackMode.SHUFFLE)

        assertEquals(Player.REPEAT_MODE_ALL, config.repeatMode)
        assertEquals(true, config.shuffleEnabled)
    }
}