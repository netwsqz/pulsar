package com.example.pulsar.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceSkipDetectorTest {

    private val config = SilenceSkipConfig(
        thresholdDb = -50.0,
        requiredDurationMs = 800L,
        tailWindowMs = 15_000L
    )

    @Test
    fun `evaluate ignores silence outside the tail window`() {
        val result = SilenceSkipDetector.evaluate(
            state = SilenceSkipState(),
            levelDb = -80.0,
            positionMs = 50_000L,
            durationMs = 100_000L,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertNull(result.state.silenceStartMs)
    }

    @Test
    fun `evaluate starts tracking silence start when entering tail window`() {
        val result = SilenceSkipDetector.evaluate(
            state = SilenceSkipState(),
            levelDb = -80.0,
            positionMs = 90_000L,
            durationMs = 100_000L,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertEquals(90_000L, result.state.silenceStartMs)
    }

    @Test
    fun `evaluate does not skip before required silence duration elapses`() {
        val state = SilenceSkipState(silenceStartMs = 90_000L)

        val result = SilenceSkipDetector.evaluate(
            state = state,
            levelDb = -80.0,
            positionMs = 90_500L,
            durationMs = 100_000L,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertEquals(90_000L, result.state.silenceStartMs)
    }

    @Test
    fun `evaluate skips once required silence duration elapses`() {
        val state = SilenceSkipState(silenceStartMs = 90_000L)

        val result = SilenceSkipDetector.evaluate(
            state = state,
            levelDb = -80.0,
            positionMs = 90_800L,
            durationMs = 100_000L,
            config = config
        )

        assertTrue(result.shouldSkip)
    }

    @Test
    fun `evaluate resets silence tracking when level rises above threshold`() {
        val state = SilenceSkipState(silenceStartMs = 90_000L)

        val result = SilenceSkipDetector.evaluate(
            state = state,
            levelDb = -10.0,
            positionMs = 90_500L,
            durationMs = 100_000L,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertNull(result.state.silenceStartMs)
    }

    @Test
    fun `evaluate does nothing when duration is unknown`() {
        val result = SilenceSkipDetector.evaluate(
            state = SilenceSkipState(silenceStartMs = 1_000L),
            levelDb = -80.0,
            positionMs = 90_000L,
            durationMs = null,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertNull(result.state.silenceStartMs)
    }

    @Test
    fun `evaluate does nothing when duration is non positive`() {
        val result = SilenceSkipDetector.evaluate(
            state = SilenceSkipState(silenceStartMs = 1_000L),
            levelDb = -80.0,
            positionMs = 0L,
            durationMs = 0L,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertNull(result.state.silenceStartMs)
    }

    @Test
    fun `evaluate resets tracking when position moves back outside the tail window`() {
        val state = SilenceSkipState(silenceStartMs = 90_000L)

        val result = SilenceSkipDetector.evaluate(
            state = state,
            levelDb = -80.0,
            positionMs = 80_000L,
            durationMs = 100_000L,
            config = config
        )

        assertFalse(result.shouldSkip)
        assertNull(result.state.silenceStartMs)
    }

    @Test
    fun `evaluate treats exact tail window boundary as inside the window`() {
        val result = SilenceSkipDetector.evaluate(
            state = SilenceSkipState(),
            levelDb = -80.0,
            positionMs = 85_000L,
            durationMs = 100_000L,
            config = config
        )

        assertEquals(85_000L, result.state.silenceStartMs)
    }

    @Test
    fun `evaluate treats position just past the tail window boundary as outside`() {
        val result = SilenceSkipDetector.evaluate(
            state = SilenceSkipState(),
            levelDb = -80.0,
            positionMs = 84_999L,
            durationMs = 100_000L,
            config = config
        )

        assertNull(result.state.silenceStartMs)
    }
}
