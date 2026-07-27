package com.astraedus.nudge.domain.autokick

/**
 * Pure decision logic for the TIME-based auto-kick trigger (`BlockRule.autoKickAfterMinutes`).
 *
 * The interaction-based trigger counts events, so it cannot fire while the user is watching
 * passively. This trigger therefore reads a CLOCK — specifically the same UsageStatsManager
 * foreground-time reading that already powers daily limits and the time-remaining overlay
 * (`UsageProvider.getDailyForegroundTimeMs`, which sums ACTIVITY_RESUMED -> ACTIVITY_PAUSED spans
 * for today and includes the live span).
 *
 * A "session" is measured as a DELTA against a baseline reading taken when the session started,
 * which gives the semantics for free:
 *  - time spent in other apps or with the screen off is not counted (the OS never reports it as
 *    foreground time), so a user who tabs out for two minutes does not burn two minutes of budget;
 *  - a brief exit and return keeps the same baseline, so the budget CONTINUES rather than resetting
 *    — matching `InteractionTracker`'s existing rule that a session survives a return within
 *    `SESSION_EXPIRY_MS`, and closing the obvious "leave and come straight back" bypass;
 *  - a genuine session end drops the baseline, and the next reading re-establishes it.
 *
 * No Android imports — fully unit-testable on the JVM.
 */
object TimeKickEvaluator {

    enum class Decision {
        /** No threshold configured for this package — do nothing. */
        DISABLED,

        /** No baseline for the current session yet; the caller must record [currentUsageMs] as one. */
        START_SESSION,

        /** Threshold not reached yet. */
        WAIT,

        /**
         * The reading went BACKWARDS relative to the baseline. Only happens across a day boundary
         * (the daily total resets at midnight) or if usage stats are cleared. Re-baseline instead of
         * kicking — a negative elapsed time is not evidence the user has overstayed.
         */
        REBASELINE,

        /** Session foreground time has reached the threshold: kick. */
        KICK
    }

    /**
     * @param thresholdMinutes `BlockRule.autoKickAfterMinutes`; null or non-positive = disabled.
     * @param baselineUsageMs the foreground-time reading taken when this session started, or null
     *   if the session has no baseline yet (fresh session, or the previous one was reset).
     * @param currentUsageMs the foreground-time reading now.
     */
    fun evaluate(
        thresholdMinutes: Int?,
        baselineUsageMs: Long?,
        currentUsageMs: Long
    ): Decision {
        if (thresholdMinutes == null || thresholdMinutes <= 0) return Decision.DISABLED
        if (baselineUsageMs == null) return Decision.START_SESSION
        if (currentUsageMs < baselineUsageMs) return Decision.REBASELINE

        val elapsedMs = currentUsageMs - baselineUsageMs
        return if (elapsedMs >= thresholdMinutes.toLong() * 60_000L) Decision.KICK else Decision.WAIT
    }
}
