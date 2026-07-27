package com.astraedus.nudge.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CounterCacheRefresherMergeTest {

    @Test
    fun `mergeEntries aggregates showTimeRemaining as OR`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = false),
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = true),
            )
        )
        assertTrue(merged["com.example.alpha"]!!.showTimeRemaining)
    }

    @Test
    fun `mergeEntries uses strictest daily limit`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = 60),
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = 30),
                "com.example.alpha" to CounterCacheEntry(dailyLimitMinutes = null),
            )
        )
        assertEquals(30, merged["com.example.alpha"]!!.dailyLimitMinutes)
    }

    @Test
    fun `mergeEntries uses longest cooldown`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickCooldownSeconds = 30),
                "com.example.alpha" to CounterCacheEntry(autoKickCooldownSeconds = 120),
                "com.example.alpha" to CounterCacheEntry(autoKickCooldownSeconds = 60),
            )
        )
        assertEquals(120, merged["com.example.alpha"]!!.autoKickCooldownSeconds)
    }

    @Test
    fun `mergeEntries handles single entry`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(
                    autoKickAfter = 20,
                    showTimeRemaining = true,
                    dailyLimitMinutes = 45,
                    autoKickCooldownSeconds = 90
                ),
            )
        )
        val entry = merged["com.example.alpha"]!!
        assertEquals(20, entry.autoKickAfter)
        assertTrue(entry.showTimeRemaining)
        assertEquals(45, entry.dailyLimitMinutes)
        assertEquals(90, entry.autoKickCooldownSeconds)
    }

    @Test
    fun `mergeEntries with all false showTimeRemaining stays false`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = false),
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = false),
            )
        )
        assertFalse(merged["com.example.alpha"]!!.showTimeRemaining)
    }

    // ── showCounter ──

    @Test
    fun `mergeEntries aggregates showCounter as OR`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showCounter = false),
                "com.example.alpha" to CounterCacheEntry(showCounter = true),
            )
        )
        assertTrue(merged["com.example.alpha"]!!.showCounter)
    }

    @Test
    fun `mergeEntries keeps showCounter false when no rule asked for it`() {
        // A package tracked only for a time-based auto-kick must not end up with a counter overlay
        // the user never enabled.
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 30),
                "com.example.alpha" to CounterCacheEntry(showTimeRemaining = true),
            )
        )
        assertFalse(merged["com.example.alpha"]!!.showCounter)
    }

    // ── autoKickAfterMinutes ──

    @Test
    fun `mergeEntries uses strictest minutes threshold`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 60),
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 15),
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = null),
            )
        )
        assertEquals(15, merged["com.example.alpha"]!!.autoKickAfterMinutes)
    }

    @Test
    fun `mergeEntries leaves minutes threshold null when no rule sets one`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(autoKickAfter = 30),
                "com.example.alpha" to CounterCacheEntry(showCounter = true),
            )
        )
        assertNull(merged["com.example.alpha"]!!.autoKickAfterMinutes)
    }

    @Test
    fun `mergeEntries keeps the two auto-kick triggers independent`() {
        val merged = CounterCacheRefresher.mergeEntries(
            listOf(
                "com.example.alpha" to CounterCacheEntry(showCounter = true, autoKickAfter = 40),
                "com.example.alpha" to CounterCacheEntry(autoKickAfterMinutes = 30),
            )
        )
        val entry = merged["com.example.alpha"]!!
        assertEquals(40, entry.autoKickAfter)
        assertEquals(30, entry.autoKickAfterMinutes)
    }

    // ── needsForegroundTimeTick ──

    @Test
    fun `a minutes threshold alone needs the foreground time tick`() {
        assertTrue(CounterCacheEntry(autoKickAfterMinutes = 30).needsForegroundTimeTick)
    }

    @Test
    fun `time remaining with a daily limit needs the foreground time tick`() {
        assertTrue(
            CounterCacheEntry(showTimeRemaining = true, dailyLimitMinutes = 60)
                .needsForegroundTimeTick
        )
    }

    @Test
    fun `time remaining without a daily limit has nothing to count down`() {
        assertFalse(CounterCacheEntry(showTimeRemaining = true).needsForegroundTimeTick)
    }

    @Test
    fun `a counter-only package does not need the foreground time tick`() {
        // Nothing clock-driven here: the counter is fed by accessibility events, so spinning a
        // timer for it would burn battery for no behaviour.
        assertFalse(
            CounterCacheEntry(showCounter = true, autoKickAfter = 30).needsForegroundTimeTick
        )
    }
}
