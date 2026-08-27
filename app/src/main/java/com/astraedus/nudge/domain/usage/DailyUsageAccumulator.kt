package com.astraedus.nudge.domain.usage

/**
 * Turns a stream of foreground events (`ACTIVITY_RESUMED` -> `ACTIVITY_PAUSED`) into per-day,
 * per-app totals, splitting any span that crosses a day boundary across the days it covers.
 *
 * **Why this exists.** The weekly screen-time bars used to come from
 * `queryUsageStats(INTERVAL_DAILY)` — pre-aggregated, stale, midnight-misaligned buckets —
 * while the day drill-down computed its numbers from live `queryEvents` spans. Two sources of
 * truth for one calendar day, and they disagreed in the field: a bar rendered tall and dark for
 * a Wednesday whose drill-down said "0s / No usage recorded". This accumulator is the ONE
 * computation both now come from (see `ScreenTimeProvider.getWeeklyUsage`), so a bar and the
 * numbers under it cannot describe different days.
 *
 * **Fed from one pass.** The caller reads the whole window with a single `queryEvents` call and
 * pushes events through in order; the alternative (one query per day) costs seven binder
 * round-trips on every 30 s stats poll, and — worse — cannot see a span that crosses midnight,
 * because such a span has one endpoint outside each day's own query.
 *
 * **Day boundaries are supplied, not derived.** They come from `TimeTracker.startOfDayDaysBefore`
 * (calendar arithmetic), so a DST day is genuinely 23 or 25 hours wide here. Deriving them from
 * `index * 86_400_000` would slide every boundary in the window an hour off true local midnight.
 *
 * Pure Kotlin, no Android types: the whole pairing/splitting contract is JVM-tested in
 * `DailyUsageAccumulatorTest`.
 *
 * Not thread-safe. Build one, feed it, [finish] it, discard it.
 *
 * @param dayBoundariesMs ascending epoch millis, one more entry than there are days: bucket `i`
 *   covers `[dayBoundariesMs[i], dayBoundariesMs[i + 1])`.
 */
class DailyUsageAccumulator(private val dayBoundariesMs: List<Long>) {

    init {
        require(dayBoundariesMs.size >= 2) {
            "need at least one day: a boundary list of ${dayBoundariesMs.size} describes no bucket"
        }
        require(dayBoundariesMs.zipWithNext().all { (start, end) -> start < end }) {
            "day boundaries must be strictly ascending, got $dayBoundariesMs"
        }
    }

    private val buckets: List<MutableMap<String, Long>> =
        List(dayBoundariesMs.size - 1) { mutableMapOf() }

    /** package -> timestamp of the RESUMED that has not been PAUSED yet. */
    private val openSpans = mutableMapOf<String, Long>()

    /**
     * A second RESUMED without an intervening PAUSED replaces the first, which is how the
     * single-day path has always read a malformed sequence: the later timestamp is the one the
     * app is demonstrably in the foreground from, and crediting the earlier one would invent
     * time the user did not spend.
     */
    fun onResumed(packageName: String, timestampMs: Long) {
        openSpans[packageName] = timestampMs
    }

    /** A PAUSED with no open RESUMED is ignored — there is no start to measure from. */
    fun onPaused(packageName: String, timestampMs: Long) {
        val startMs = openSpans.remove(packageName) ?: return
        addSpan(packageName, startMs, timestampMs)
    }

    /**
     * Closes the accumulation and returns one map per day.
     *
     * A span still open at the end of the stream is counted **up to [nowMs]**, and only when the
     * window actually reaches the present ([windowEndMs] >= [nowMs]) — exactly what the
     * single-day path does for "today". For a window that has already ended, an open span means
     * the closing PAUSED simply fell outside the query, and inventing an end for it would credit
     * a past day with time we cannot see.
     *
     * Idempotent: open spans are consumed, so a second call adds nothing.
     */
    fun finish(windowEndMs: Long, nowMs: Long): List<Map<String, Long>> {
        if (windowEndMs >= nowMs) {
            for ((packageName, startMs) in openSpans) addSpan(packageName, startMs, nowMs)
        }
        openSpans.clear()
        return buckets.map { it.toMap() }
    }

    /**
     * Adds `[startMs, endMs)` to every day it overlaps.
     *
     * Clamped to the window first, so an event from outside it contributes nothing, and a
     * backwards pair (PAUSED before RESUMED — a corrupt sequence the platform has produced)
     * yields zero rather than the negative duration a bare subtraction would.
     */
    private fun addSpan(packageName: String, startMs: Long, endMs: Long) {
        val spanStart = startMs.coerceAtLeast(dayBoundariesMs.first())
        val spanEnd = endMs.coerceAtMost(dayBoundariesMs.last())
        if (spanStart >= spanEnd) return

        for (day in buckets.indices) {
            val dayStart = dayBoundariesMs[day]
            if (dayStart >= spanEnd) break
            val dayEnd = dayBoundariesMs[day + 1]
            if (dayEnd <= spanStart) continue

            val overlap = minOf(spanEnd, dayEnd) - maxOf(spanStart, dayStart)
            if (overlap > 0L) {
                buckets[day][packageName] = (buckets[day][packageName] ?: 0L) + overlap
            }
        }
    }
}
