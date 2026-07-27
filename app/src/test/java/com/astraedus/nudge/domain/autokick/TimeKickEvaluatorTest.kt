package com.astraedus.nudge.domain.autokick

import com.astraedus.nudge.domain.autokick.TimeKickEvaluator.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the time-based auto-kick threshold decision.
 *
 * This is the whole safety-critical core of the feature: a false KICK throws a user out of an app
 * they had barely opened, and a missed KICK is the feature silently not working. Every branch is
 * pinned, including the two that exist only to protect against a bad reading.
 */
class TimeKickEvaluatorTest {

    private val thirtyMinutesMs = 30L * 60_000L

    // ── disabled ──

    @Test
    fun `null threshold is disabled`() {
        assertEquals(Decision.DISABLED, TimeKickEvaluator.evaluate(null, 0L, thirtyMinutesMs))
    }

    @Test
    fun `zero threshold is disabled, never an instant kick`() {
        // A stored 0 must not mean "kick after 0 minutes" -- that would eject the user the moment
        // they opened the app. The UI treats blank/0 as off; storage has to agree.
        assertEquals(Decision.DISABLED, TimeKickEvaluator.evaluate(0, 0L, thirtyMinutesMs))
    }

    @Test
    fun `negative threshold is disabled`() {
        assertEquals(Decision.DISABLED, TimeKickEvaluator.evaluate(-5, 0L, thirtyMinutesMs))
    }

    // ── session start ──

    @Test
    fun `missing baseline starts a session rather than kicking`() {
        // The reading is a DAILY total, so a user who already spent hours in the app earlier today
        // must not be kicked the instant a fresh session begins.
        assertEquals(
            Decision.START_SESSION,
            TimeKickEvaluator.evaluate(30, null, 5L * 3600_000L)
        )
    }

    // ── waiting ──

    @Test
    fun `elapsed below threshold waits`() {
        assertEquals(Decision.WAIT, TimeKickEvaluator.evaluate(30, 1_000L, 1_000L + thirtyMinutesMs - 1))
    }

    @Test
    fun `no elapsed time waits`() {
        assertEquals(Decision.WAIT, TimeKickEvaluator.evaluate(30, 1_000L, 1_000L))
    }

    // ── kicking ──

    @Test
    fun `elapsed exactly at threshold kicks`() {
        assertEquals(Decision.KICK, TimeKickEvaluator.evaluate(30, 1_000L, 1_000L + thirtyMinutesMs))
    }

    @Test
    fun `elapsed past threshold kicks`() {
        // The clock ticks on an interval, so a reading always lands somewhere past the boundary.
        assertEquals(Decision.KICK, TimeKickEvaluator.evaluate(30, 0L, thirtyMinutesMs + 29_000L))
    }

    @Test
    fun `only time since the baseline counts, not the whole daily total`() {
        // 5h already on the clock today, session started 10 minutes ago, threshold 30 -> wait.
        val fiveHours = 5L * 3600_000L
        assertEquals(
            Decision.WAIT,
            TimeKickEvaluator.evaluate(30, fiveHours, fiveHours + 10L * 60_000L)
        )
    }

    @Test
    fun `one minute threshold kicks after one minute`() {
        assertEquals(Decision.KICK, TimeKickEvaluator.evaluate(1, 0L, 60_000L))
        assertEquals(Decision.WAIT, TimeKickEvaluator.evaluate(1, 0L, 59_999L))
    }

    @Test
    fun `a full day threshold does not overflow`() {
        val oneDayMs = 1440L * 60_000L
        assertEquals(Decision.WAIT, TimeKickEvaluator.evaluate(1440, 0L, oneDayMs - 1))
        assertEquals(Decision.KICK, TimeKickEvaluator.evaluate(1440, 0L, oneDayMs))
    }

    // ── backwards readings ──

    @Test
    fun `reading below the baseline re-baselines instead of kicking`() {
        // Happens across midnight: the daily foreground total resets to ~0 while the session
        // baseline still holds yesterday's figure. A negative elapsed time is not evidence the
        // user overstayed, so it must never be read as one.
        assertEquals(Decision.REBASELINE, TimeKickEvaluator.evaluate(30, 5L * 3600_000L, 1_000L))
    }

    @Test
    fun `reading one millisecond below the baseline re-baselines`() {
        assertEquals(Decision.REBASELINE, TimeKickEvaluator.evaluate(30, 1_000L, 999L))
    }

    @Test
    fun `a disabled threshold wins over a backwards reading`() {
        assertEquals(Decision.DISABLED, TimeKickEvaluator.evaluate(null, 5_000L, 0L))
    }
}
