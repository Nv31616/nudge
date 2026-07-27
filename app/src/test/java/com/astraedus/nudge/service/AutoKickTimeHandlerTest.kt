package com.astraedus.nudge.service

import com.astraedus.nudge.domain.logging.NudgeLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the time-based auto-kick handler against the real [InteractionTracker] and
 * [CounterCacheRefresher] with only the clock faked, so the session-marker bookkeeping that decides
 * when a user's budget restarts is exercised end to end.
 */
class AutoKickTimeHandlerTest {

    private val pkg = "com.example.video"

    private lateinit var tracker: InteractionTracker
    private lateinit var counterCache: CounterCacheRefresher
    private lateinit var usage: FakeUsageProvider
    private lateinit var handler: AutoKickTimeHandler
    private var fakeTime = 1_000_000L

    @Before
    fun setUp() {
        fakeTime = 1_000_000L
        tracker = InteractionTracker()
        tracker.clock = { fakeTime }
        counterCache = CounterCacheRefresher()
        usage = FakeUsageProvider()
        handler = AutoKickTimeHandler(
            counterCache = counterCache,
            interactionTracker = tracker,
            usageProvider = usage,
            logger = NudgeLog.NoOp
        )
    }

    private fun cache(entry: CounterCacheEntry, packageName: String = pkg) {
        runBlocking { counterCache.forceRefresh { mapOf(packageName to entry) } }
    }

    private fun minutes(n: Long): Long = n * 60_000L

    // ── disabled ──

    @Test
    fun `no cache entry never kicks`() {
        usage.foregroundMs = minutes(600)

        assertFalse(handler.shouldKick(pkg))
    }

    @Test
    fun `entry without a minutes threshold never kicks`() {
        cache(CounterCacheEntry(showCounter = true, autoKickAfter = 30))
        usage.foregroundMs = minutes(600)

        assertFalse(handler.shouldKick(pkg))
        assertNull(tracker.getSessionUsageBaseline(pkg))
    }

    // ── session baseline ──

    @Test
    fun `first check records the baseline and does not kick`() {
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        // Five hours already spent in this app earlier today. A daily total must not be mistaken
        // for session time -- this is the "kicked the instant I opened it" failure mode.
        usage.foregroundMs = minutes(300)

        assertFalse(handler.shouldKick(pkg))
        assertEquals(minutes(300), tracker.getSessionUsageBaseline(pkg))
    }

    @Test
    fun `kicks once the threshold is reached from the baseline`() {
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        usage.foregroundMs = minutes(300)
        handler.shouldKick(pkg)

        usage.foregroundMs = minutes(329)
        assertFalse(handler.shouldKick(pkg))

        usage.foregroundMs = minutes(330)
        assertTrue(handler.shouldKick(pkg))
    }

    /** Models what the service does: enter app, then tick the clock. */
    private fun enter(packageName: String = pkg) = tracker.onAppChanged(packageName)

    @Test
    fun `time spent in other apps does not count toward the session`() {
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        enter()
        usage.foregroundMs = minutes(10)
        handler.shouldKick(pkg)

        // User leaves for 20 minutes and comes back inside the session window, so the baseline
        // survives. The OS reports no foreground time for the app while they were away, so those
        // 20 minutes simply are not in the reading.
        enter("com.example.other")
        fakeTime += minutes(2)
        enter()

        usage.foregroundMs = minutes(25)
        assertFalse(handler.shouldKick(pkg))
        assertEquals(minutes(10), tracker.getSessionUsageBaseline(pkg))
    }

    @Test
    fun `leaving and returning quickly does not reset the budget`() {
        // The bypass this closes: burn 29 of 30 minutes, tab out, tab straight back, expect a
        // fresh 30. The baseline must survive, so the 30th minute still kicks.
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        enter()
        usage.foregroundMs = 0L
        handler.shouldKick(pkg)

        usage.foregroundMs = minutes(29)
        enter("com.example.other")
        fakeTime += 10_000L
        enter()
        assertEquals(0L, tracker.getSessionUsageBaseline(pkg))

        usage.foregroundMs = minutes(30)
        assertTrue(handler.shouldKick(pkg))
    }

    @Test
    fun `a genuine session break restarts the budget`() {
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        enter()
        usage.foregroundMs = 0L
        handler.shouldKick(pkg)
        usage.foregroundMs = minutes(29)

        // Away for longer than the session-expiry window -> new session, fresh budget.
        enter("com.example.other")
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        enter()
        assertNull(tracker.getSessionUsageBaseline(pkg))

        assertFalse(handler.shouldKick(pkg))
        assertEquals(minutes(29), tracker.getSessionUsageBaseline(pkg))

        usage.foregroundMs = minutes(58)
        assertFalse(handler.shouldKick(pkg))
        usage.foregroundMs = minutes(59)
        assertTrue(handler.shouldKick(pkg))
    }

    @Test
    fun `the interaction count and the time baseline reset together`() {
        // One definition of "session" for both triggers -- they must never disagree about whether
        // the user is still in the same sitting.
        cache(CounterCacheEntry(showCounter = true, autoKickAfter = 30, autoKickAfterMinutes = 30))
        enter()
        usage.foregroundMs = minutes(5)
        handler.shouldKick(pkg)
        tracker.recordInteraction(pkg)
        tracker.recordInteraction(pkg)

        enter("com.example.other")
        fakeTime += InteractionTracker.SESSION_EXPIRY_MS + 1
        enter()

        assertEquals(0, tracker.getSessionCount(pkg))
        assertNull(tracker.getSessionUsageBaseline(pkg))
    }

    @Test
    fun `resetSession clears the baseline so the next session starts fresh`() {
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        usage.foregroundMs = minutes(10)
        handler.shouldKick(pkg)

        // This is what AutoKickExecutor does immediately after a kick.
        tracker.resetSession(pkg)
        assertNull(tracker.getSessionUsageBaseline(pkg))

        usage.foregroundMs = minutes(40)
        assertFalse(handler.shouldKick(pkg))
        assertEquals(minutes(40), tracker.getSessionUsageBaseline(pkg))
    }

    // ── bad readings ──

    @Test
    fun `a backwards reading re-baselines instead of kicking`() {
        cache(CounterCacheEntry(autoKickAfterMinutes = 30))
        usage.foregroundMs = minutes(300)
        handler.shouldKick(pkg)

        // Midnight: the daily foreground total resets while the session is still open.
        usage.foregroundMs = minutes(1)
        assertFalse(handler.shouldKick(pkg))
        assertEquals(minutes(1), tracker.getSessionUsageBaseline(pkg))

        usage.foregroundMs = minutes(31)
        assertTrue(handler.shouldKick(pkg))
    }

    @Test
    fun `a failing usage read never kicks`() {
        // No usage-stats permission, or the OS throwing: an unreadable clock must fail toward
        // leaving the user alone, never toward ejecting them from an app.
        cache(CounterCacheEntry(autoKickAfterMinutes = 1))
        usage.throwOnRead = true

        assertFalse(handler.shouldKick(pkg))
        assertNull(tracker.getSessionUsageBaseline(pkg))
    }

    @Test
    fun `each package keeps its own baseline`() {
        val other = "com.example.other"
        runBlocking {
            counterCache.forceRefresh {
                mapOf(
                    pkg to CounterCacheEntry(autoKickAfterMinutes = 30),
                    other to CounterCacheEntry(autoKickAfterMinutes = 30)
                )
            }
        }

        usage.perPackage[pkg] = minutes(100)
        usage.perPackage[other] = minutes(5)
        handler.shouldKick(pkg)
        handler.shouldKick(other)

        assertEquals(minutes(100), tracker.getSessionUsageBaseline(pkg))
        assertEquals(minutes(5), tracker.getSessionUsageBaseline(other))

        usage.perPackage[pkg] = minutes(130)
        assertTrue(handler.shouldKick(pkg))
        assertFalse(handler.shouldKick(other))
    }

    private class FakeUsageProvider : UsageProvider {
        var foregroundMs: Long = 0L
        var throwOnRead: Boolean = false
        val perPackage = mutableMapOf<String, Long>()

        override fun getDailyForegroundTimeMs(packageName: String): Long {
            if (throwOnRead) throw IllegalStateException("usage stats unavailable")
            return perPackage[packageName] ?: foregroundMs
        }
    }
}
