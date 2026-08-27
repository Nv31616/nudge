package com.astraedus.nudge.data.repository

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.usage.DailyUsageAccumulator
import com.astraedus.nudge.domain.usage.WeeklyUsage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides screen time data from Android's UsageStatsManager.
 *
 * This is the correct data source for "Screen Time" display. The internal
 * Room DB (usage_events table) only logs block/allow decisions and does NOT
 * track foreground duration at all — it carried an always-zero `durationMs`
 * column until issue #22 removed it.
 *
 * Requires PACKAGE_USAGE_STATS permission (granted via Settings > Special Access > Usage Access).
 * Returns 0 gracefully when permission is missing.
 */
@Singleton
class ScreenTimeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timeTracker: TimeTracker
) {

    /** Foreground time for one app over a range, plus how many sessions it was spread over. */
    data class SessionStats(val totalMs: Long, val sessionCount: Int) {
        /** Mean session length, or null when there is nothing to average. */
        val averageMs: Long? get() = if (sessionCount > 0) totalMs / sessionCount else null
    }

    private val usageStatsManager: UsageStatsManager? by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    }

    /** Check if Usage Access permission is granted. */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Per-app foreground time AND the number of foreground sessions that produced it, for
     * an arbitrary range — one `queryEvents` pass, one place that pairs RESUMED with PAUSED.
     *
     * This is a RANGE primitive, not a day one. Day-scoped and week-scoped totals all come from
     * [getWeeklyUsage] instead, so the bars and the drill-down under them can never be two
     * different computations of the same calendar day (they were, and they disagreed).
     *
     * The session count is what makes an *average session length* computable
     * (`totalMs / sessionCount`), which the Willpower screen uses to estimate how much
     * time a walk-away actually saved.
     */
    fun getPerAppSessionStats(rangeStartMs: Long, rangeEndMs: Long): Map<String, SessionStats> {
        return try {
            val usm = usageStatsManager ?: return emptyMap()
            val now = System.currentTimeMillis()
            val effectiveEnd = rangeEndMs.coerceAtMost(now)
            if (rangeStartMs >= effectiveEnd) return emptyMap()

            val events = usm.queryEvents(rangeStartMs, effectiveEnd) ?: return emptyMap()
            val event = UsageEvents.Event()

            val foregroundStarts = mutableMapOf<String, Long>()
            val perApp = mutableMapOf<String, SessionStats>()

            fun record(pkg: String, durationMs: Long) {
                val existing = perApp[pkg] ?: SessionStats(0L, 0)
                perApp[pkg] = SessionStats(
                    totalMs = existing.totalMs + durationMs,
                    sessionCount = existing.sessionCount + 1
                )
            }

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foregroundStarts[event.packageName] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val startTime = foregroundStarts.remove(event.packageName)
                        if (startTime != null) {
                            record(event.packageName, event.timeStamp - startTime)
                        }
                    }
                }
            }

            // Only add still-open sessions if the range includes "now"
            if (rangeEndMs >= now) {
                for ((pkg, startTime) in foregroundStarts) {
                    record(pkg, now - startTime)
                }
            }

            perApp
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    /**
     * Per-day, per-app foreground time for the [WEEK_DAYS]-day window ending at [lastDayStartMs].
     *
     * **The one source of truth for every day-scoped screen-time number in the app** — the weekly
     * bars, the day drill-down's hero total, and its per-app list all read this single value.
     *
     * It replaced a `queryUsageStats(INTERVAL_DAILY)` series that ran beside the drill-down's
     * event-based computation. Those pre-aggregated buckets are stale and midnight-misaligned on
     * Android 12+ (see [getPerAppSessionStats]), so the two could flatly contradict each other:
     * a Wednesday bar rendered tall and dark while drilling into that Wednesday showed "0s" and
     * "No usage recorded". Both are now the same numbers, not merely two computations expected
     * to agree.
     *
     * **One pass, not seven.** A single `queryEvents` over the whole window feeds
     * [DailyUsageAccumulator], which splits each RESUMED->PAUSED span across the days it covers.
     * Seven per-day queries would cost seven binder round-trips on every 30 s Home/Stats poll on
     * a 3 GB Pixel 3, and could not see a session crossing midnight at all — each half would be
     * an unpaired event in its own day's query and would be dropped from both.
     *
     * Day boundaries come from `TimeTracker.startOfDayDaysBefore` (calendar arithmetic), so they
     * are true local midnights either side of a DST transition.
     *
     * Returns a zeroed window (right shape, no data) without permission, on a read failure, or
     * for a window that lies entirely in the future.
     *
     * @param lastDayStartMs start-of-day epoch millis for the last day of the window (default: today)
     */
    fun getWeeklyUsage(lastDayStartMs: Long = timeTracker.startOfToday()): WeeklyUsage {
        val dayStarts = (WEEK_DAYS - 1 downTo 0).map { daysAgo ->
            timeTracker.startOfDayDaysBefore(lastDayStartMs, daysAgo)
        }
        // A negative "days before" walks forward: the exclusive end of the window is the start of
        // the day AFTER the last one. Calendar arithmetic again, not `+ DAY_MS`.
        val windowEndMs = timeTracker.startOfDayDaysBefore(lastDayStartMs, -1)
        val windowStartMs = dayStarts.first()

        return try {
            val usm = usageStatsManager ?: return WeeklyUsage.empty(dayStarts)
            val now = System.currentTimeMillis()
            val effectiveEnd = windowEndMs.coerceAtMost(now)
            if (windowStartMs >= effectiveEnd) return WeeklyUsage.empty(dayStarts)

            val events = usm.queryEvents(windowStartMs, effectiveEnd)
                ?: return WeeklyUsage.empty(dayStarts)
            val event = UsageEvents.Event()
            val accumulator = DailyUsageAccumulator(dayStarts + windowEndMs)

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED ->
                        accumulator.onResumed(event.packageName, event.timeStamp)
                    UsageEvents.Event.ACTIVITY_PAUSED ->
                        accumulator.onPaused(event.packageName, event.timeStamp)
                }
            }

            WeeklyUsage(dayStarts, accumulator.finish(windowEndMs = windowEndMs, nowMs = now))
        } catch (_: SecurityException) {
            WeeklyUsage.empty(dayStarts)
        }
    }

    /**
     * Get per-hour screen time breakdown for an arbitrary day.
     * Returns a list of 24 entries (index = hour 0-23), each value in milliseconds.
     *
     * @param dayStartMs start of the day (midnight), epoch millis
     * @param dayEndMs end of the day (next midnight or now for today), epoch millis
     */
    fun getHourlyScreenTime(dayStartMs: Long, dayEndMs: Long): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(24) { 0L }
            val now = System.currentTimeMillis()
            val effectiveEnd = dayEndMs.coerceAtMost(now)
            if (dayStartMs >= effectiveEnd) return List(24) { 0L }

            val hourMs = 60L * 60L * 1000L
            val hourly = MutableList(24) { 0L }

            val events = usm.queryEvents(dayStartMs, effectiveEnd) ?: return hourly
            val event = UsageEvents.Event()

            val foregroundStarts = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        foregroundStarts[event.packageName] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val startTime = foregroundStarts.remove(event.packageName)
                        if (startTime != null) {
                            distributeToHours(hourly, startTime, event.timeStamp, dayStartMs, hourMs)
                        }
                    }
                }
            }

            if (dayEndMs >= now) {
                for ((_, startTime) in foregroundStarts) {
                    distributeToHours(hourly, startTime, now, dayStartMs, hourMs)
                }
            }

            hourly
        } catch (_: SecurityException) {
            List(24) { 0L }
        }
    }

    /** Convenience: get per-hour screen time breakdown for today. */
    fun getHourlyScreenTimeToday(): List<Long> {
        val todayStart = timeTracker.startOfToday()
        val now = System.currentTimeMillis()
        return getHourlyScreenTime(todayStart, now)
    }

    /**
     * Get per-hour screen time breakdown for a specific app on an arbitrary day.
     *
     * @param packageName the app's package name
     * @param dayStartMs start of the day (midnight), epoch millis
     * @param dayEndMs end of the day (next midnight or now for today), epoch millis
     */
    fun getPerAppHourlyScreenTime(packageName: String, dayStartMs: Long, dayEndMs: Long): List<Long> {
        return try {
            val usm = usageStatsManager ?: return List(24) { 0L }
            val now = System.currentTimeMillis()
            val effectiveEnd = dayEndMs.coerceAtMost(now)
            if (dayStartMs >= effectiveEnd) return List(24) { 0L }

            val hourMs = 60L * 60L * 1000L
            val hourly = MutableList(24) { 0L }

            val events = usm.queryEvents(dayStartMs, effectiveEnd) ?: return hourly
            val event = UsageEvents.Event()
            val foregroundStarts = mutableMapOf<String, Long>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.packageName != packageName) continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foregroundStarts[event.packageName] = event.timeStamp
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        val startTime = foregroundStarts.remove(event.packageName)
                        if (startTime != null) {
                            distributeToHours(hourly, startTime, event.timeStamp, dayStartMs, hourMs)
                        }
                    }
                }
            }

            if (dayEndMs >= now) {
                for ((_, startTime) in foregroundStarts) {
                    distributeToHours(hourly, startTime, now, dayStartMs, hourMs)
                }
            }

            hourly
        } catch (_: SecurityException) {
            List(24) { 0L }
        }
    }

    /** Convenience: get per-hour screen time for a specific app today. */
    fun getPerAppHourlyScreenTimeToday(packageName: String): List<Long> {
        val todayStart = timeTracker.startOfToday()
        val now = System.currentTimeMillis()
        return getPerAppHourlyScreenTime(packageName, todayStart, now)
    }

    /**
     * Distribute a foreground session's duration across hourly buckets.
     */
    private fun distributeToHours(
        hourly: MutableList<Long>,
        sessionStart: Long,
        sessionEnd: Long,
        todayStart: Long,
        hourMs: Long
    ) {
        val clampedStart = sessionStart.coerceAtLeast(todayStart)
        val clampedEnd = sessionEnd.coerceAtMost(todayStart + 24 * hourMs)

        if (clampedStart >= clampedEnd) return

        val startHour = ((clampedStart - todayStart) / hourMs).toInt().coerceIn(0, 23)
        val endHour = ((clampedEnd - todayStart) / hourMs).toInt().coerceIn(0, 23)

        for (hour in startHour..endHour) {
            val bucketStart = todayStart + hour * hourMs
            val bucketEnd = bucketStart + hourMs
            val overlapStart = clampedStart.coerceAtLeast(bucketStart)
            val overlapEnd = clampedEnd.coerceAtMost(bucketEnd)
            if (overlapStart < overlapEnd) {
                hourly[hour] += overlapEnd - overlapStart
            }
        }
    }

    companion object {
        /**
         * Days in a weekly window. Must match `StatsDaySelection.WINDOW_DAYS` — the screens index
         * into [WeeklyUsage] by the bar the user tapped. Pinned by `WeeklyUsageTest`.
         */
        const val WEEK_DAYS = 7
    }
}
