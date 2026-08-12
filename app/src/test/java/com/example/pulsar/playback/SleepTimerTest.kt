package com.example.pulsar.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    @Test
    fun `start activates timer and counts down every second`() = runTest {
        val engine = SleepTimerEngine(backgroundScope)

        engine.start(5_000)
        assertTrue(engine.state.value.isActive)
        assertEquals(5_000L, engine.state.value.remainingMs)

        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(1_000L, engine.state.value.remainingMs)
        assertTrue(engine.state.value.isActive)
    }

    @Test
    fun `timer fires onTimeout once and resets to inactive`() = runTest {
        val engine = SleepTimerEngine(backgroundScope)
        var timeoutCalls = 0
        engine.onTimeout = { timeoutCalls++ }

        engine.start(5_000)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, timeoutCalls)
        assertFalse(engine.state.value.isActive)
        assertEquals(0L, engine.state.value.remainingMs)
    }

    @Test
    fun `cancel resets state and does not fire timeout`() = runTest {
        val engine = SleepTimerEngine(backgroundScope)
        var timeoutCalls = 0
        engine.onTimeout = { timeoutCalls++ }

        engine.start(60_000)
        advanceTimeBy(30_000)
        engine.cancel()

        assertFalse(engine.state.value.isActive)
        assertEquals(0L, engine.state.value.remainingMs)

        advanceTimeBy(60_000)
        assertEquals(0, timeoutCalls)
    }

    @Test
    fun `restart overrides previous timer`() = runTest {
        val engine = SleepTimerEngine(backgroundScope)
        var timeoutCalls = 0
        engine.onTimeout = { timeoutCalls++ }

        engine.start(60_000)
        advanceTimeBy(10_000)
        runCurrent()
        engine.start(5_000)

        assertEquals(5_000L, engine.state.value.remainingMs)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, timeoutCalls)
        assertFalse(engine.state.value.isActive)
    }

    @Test
    fun `start with non-positive duration throws`() = runTest {
        val engine = SleepTimerEngine(backgroundScope)

        assertStartThrows(engine, 0)
        assertStartThrows(engine, -1)
    }

    @Test
    fun `fraction reflects remaining proportion and zero when idle`() = runTest {
        val engine = SleepTimerEngine(backgroundScope)

        engine.start(60_000)
        assertEquals(1f, engine.state.value.fraction)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(0.5f, engine.state.value.fraction, 0.001f)

        engine.cancel()
        assertFalse(engine.state.value.isActive)
        assertEquals(0f, engine.state.value.fraction)
    }

    private fun assertStartThrows(engine: SleepTimerEngine, durationMs: Long) {
        try {
            engine.start(durationMs)
            fail("start($durationMs) 应抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期异常
        }
    }
}
