package com.astraedus.nudge.domain.usage

/**
 * Screen time for a window of consecutive days, per day and per app.
 *
 * This is the single value the day-scoped stats screens read: the bars are [dailyTotals], the
 * drill-down under them is [perAppOn] / [totalOn] for the selected day. Because both come out of
 * one object built by one pass over one event stream, a bar and the numbers beneath it cannot
 * disagree — which they did, badly, when the series came from `queryUsageStats(INTERVAL_DAILY)`
 * and the drill-down from `queryEvents`.
 *
 * Days are addressed by their **start timestamp**, not by index, wherever a caller has one.
 * Index lookups are fine within a single value, but a screen holds its selection separately from
 * the loaded window, and during the frame after the window moves an index would silently point
 * at a different date. [perAppOn] answers "the day that starts here", or nothing.
 *
 * Pure Kotlin; JVM-tested in `WeeklyUsageTest`.
 *
 * @param dayStartsMs epoch millis of each day's local midnight, ascending, oldest first.
 * @param perDayPerApp package -> foreground millis, one map per entry in [dayStartsMs]. Only
 *   packages with time actually spent appear; there are no zero-valued entries.
 */
class WeeklyUsage(
    val dayStartsMs: List<Long>,
    private val perDayPerApp: List<Map<String, Long>>
) {

    init {
        require(dayStartsMs.size == perDayPerApp.size) {
            "${dayStartsMs.size} day starts but ${perDayPerApp.size} day buckets"
        }
    }

    val days: Int get() = dayStartsMs.size

    /** The last day of the window — "today" whenever the window is the live one. */
    val lastDayStartMs: Long get() = dayStartsMs.lastOrNull() ?: 0L

    /** Per-app time on day [dayIndex]; empty for an index outside the window. */
    fun perApp(dayIndex: Int): Map<String, Long> = perDayPerApp.getOrElse(dayIndex) { emptyMap() }

    /**
     * Per-app time on the day starting at [dayStartMs], or empty when this window does not
     * contain that day.
     *
     * Empty is the deliberate answer for a day we do not hold: showing the neighbouring day's
     * numbers under this day's heading is precisely the "the chart and the numbers describe
     * different days" defect this type exists to make impossible.
     *
     * Matched to the NEAREST day start, within [SAME_DAY_TOLERANCE_MS], rather than by equality.
     * The caller's day start comes from `java.time` (`LocalDate.atStartOfDay`) while these come
     * from `Calendar`, and in the handful of zones whose DST transition happens AT midnight the
     * two normalise a non-existent local midnight to the same instant only by convention. An
     * exact match that missed would blank a real day — the very symptom being fixed — while days
     * here are at least 23 hours apart, so half a day of slack cannot reach a neighbour.
     */
    fun perAppOn(dayStartMs: Long): Map<String, Long> = perApp(indexOfDay(dayStartMs))

    private fun indexOfDay(dayStartMs: Long): Int {
        var nearest = -1
        var nearestDistance = Long.MAX_VALUE
        dayStartsMs.forEachIndexed { index, start ->
            val distance = kotlin.math.abs(start - dayStartMs)
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = index
            }
        }
        return if (nearestDistance <= SAME_DAY_TOLERANCE_MS) nearest else -1
    }

    /** Total across all apps on the day starting at [dayStartMs]. */
    fun totalOn(dayStartMs: Long): Long = perAppOn(dayStartMs).values.sum()

    /** One total per day, oldest first — the weekly bars. */
    fun dailyTotals(): List<Long> = perDayPerApp.map { day -> day.values.sum() }

    /** One total per day for a single app — the App Detail bars. */
    fun dailyTotalsFor(packageName: String): List<Long> =
        perDayPerApp.map { day -> day[packageName] ?: 0L }

    companion object {
        /**
         * How far a requested day start may sit from a real one and still mean the same day.
         * Half a day: enough to absorb a DST normalisation hour, far short of the >= 23 hours
         * that separate two days.
         */
        private const val SAME_DAY_TOLERANCE_MS = 12L * 60L * 60L * 1000L

        /** A window with the right shape and no data: no permission, no events, or a future window. */
        fun empty(dayStartsMs: List<Long>): WeeklyUsage =
            WeeklyUsage(dayStartsMs, dayStartsMs.map { emptyMap() })
    }
}
