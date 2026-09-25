package com.example.pulsar.playback

import com.example.pulsar.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ===== positionReachedSeekTarget：松手 seek 后播放器是否已追上拖动目标 =====

    @Test
    fun `位置与目标完全一致时视为已追上`() {
        assertTrue(positionReachedSeekTarget(30f, 30f))
    }

    @Test
    fun `位置在默认容差内视为已追上`() {
        assertTrue(positionReachedSeekTarget(30.5f, 30f))
        assertTrue(positionReachedSeekTarget(29.5f, 30f))
    }

    @Test
    fun `位置还停在拖动前的旧位置时视为未追上`() {
        assertFalse(positionReachedSeekTarget(5f, 30f))
    }

    @Test
    fun `向后拖与向前拖都按绝对差值判断`() {
        assertFalse(positionReachedSeekTarget(60f, 5f))
        assertTrue(positionReachedSeekTarget(5.4f, 5f))
    }

    @Test
    fun `可自定义容差`() {
        assertTrue(positionReachedSeekTarget(30.5f, 30f, toleranceSec = 0.6f))
        assertFalse(positionReachedSeekTarget(30.5f, 30f, toleranceSec = 0.2f))
    }

    @Test
    fun `任一侧为非有限值时一律视为已追上以放开锁定`() {
        assertTrue(positionReachedSeekTarget(Float.NaN, 30f))
        assertTrue(positionReachedSeekTarget(30f, Float.NaN))
        assertTrue(positionReachedSeekTarget(Float.POSITIVE_INFINITY, 30f))
        assertTrue(positionReachedSeekTarget(30f, Float.NEGATIVE_INFINITY))
    }
}
