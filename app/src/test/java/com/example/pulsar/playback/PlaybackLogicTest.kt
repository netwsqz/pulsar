package com.example.pulsar.playback

import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackLogicTest {

    private val tracks = listOf(
        Track("t1", "歌一", "艺人A", "专辑X", 100_000, null),
        Track("t2", "歌二", "艺人B", "专辑X", 200_000, null),
        Track("t3", "歌三", "艺人C", "专辑X", 150_000, null)
    )

    @Test
    fun `buildPlaybackOrder rotates list so clicked track plays first`() {
        val order = buildPlaybackOrder(tracks, "t2")

        assertEquals(listOf("t2", "t3", "t1"), order!!.map { it.id })
    }

    @Test
    fun `buildPlaybackOrder starting from first track keeps original order`() {
        val order = buildPlaybackOrder(tracks, "t1")

        assertEquals(listOf("t1", "t2", "t3"), order!!.map { it.id })
    }

    @Test
    fun `buildPlaybackOrder starting from last track wraps around`() {
        val order = buildPlaybackOrder(tracks, "t3")

        assertEquals(listOf("t3", "t1", "t2"), order!!.map { it.id })
    }

    @Test
    fun `buildPlaybackOrder returns null for unknown track id`() {
        assertNull(buildPlaybackOrder(tracks, "missing"))
    }

    @Test
    fun `buildPlaybackOrder returns null for empty list`() {
        assertNull(buildPlaybackOrder(emptyList(), "t1"))
    }

    @Test
    fun `buildPlaybackOrder with single track returns just that track`() {
        val order = buildPlaybackOrder(listOf(tracks.first()), "t1")

        assertEquals(listOf("t1"), order!!.map { it.id })
    }

    @Test
    fun `formatPosition renders minutes and zero padded seconds`() {
        assertEquals("0:00", formatPosition(0))
        assertEquals("0:59", formatPosition(59_000))
        assertEquals("1:05", formatPosition(65_000))
        assertEquals("30:00", formatPosition(1_800_000))
    }

    @Test
    fun `formatPosition clamps negative or unknown durations to zero`() {
        assertEquals("0:00", formatPosition(-1))
        assertEquals("0:00", formatPosition(Long.MIN_VALUE))
    }
}
