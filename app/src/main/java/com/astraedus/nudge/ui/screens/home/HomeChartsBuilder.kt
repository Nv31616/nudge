package com.astraedus.nudge.ui.screens.home

import androidx.compose.runtime.Immutable
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.ui.screens.stats.StatsCalculator
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import javax.inject.Inject

@Immutable
data class HomeCharts(
    /** 7 daily screen-time totals, oldest first, last entry = today. */
    val weeklyScreenTime: List<DayData> = emptyList(),
    /** Blocked / walked-away counts over the same 7 days. */
    val weeklyTrend: List<TrendDay> = emptyList(),
    val weekTotalMs: Long = 0L,
    val weekBlocked: Int = 0,
    val weekWalkedAway: Int = 0
) {
    /** True when there is literally nothing to draw, so the home cards can stay collapsed. */
    val isEmpty: Boolean
        get() = weekTotalMs == 0L && weekBlocked == 0 && weekWalkedAway == 0
}

/**
 * Derives the home dashboard's two mini charts from data the app already loads.
 *
 * Kept out of [HomeViewModel] and free of Android types so the derivation is JVM-tested:
 * the home screen is the first thing that renders on a 3GB Pixel 3, and the arithmetic behind
 * it should not need an emulator to verify. It reuses [StatsCalculator] rather than
 * re-deriving day buckets, so home and the stats screen can never bucket a day differently.
 */
class HomeChartsBuilder @Inject constructor(
    private val statsCalculator: StatsCalculator
) {

    /**
     * @param weeklyTotals 7 pre-computed daily screen-time totals from `ScreenTimeProvider`,
     *   index 0 = 6 days ago, index 6 = [todayStartMs]'s day.
     * @param weekEvents `usage_events` covering at least the same 7 days.
     */
    fun build(
        weeklyTotals: List<Long>,
        weekEvents: List<UsageEvent>,
        todayStartMs: Long
    ): HomeCharts {
        val trend = statsCalculator.buildTrendData(weekEvents, todayStartMs)
        return HomeCharts(
            weeklyScreenTime = statsCalculator.buildWeeklyDataFromTotals(weeklyTotals, todayStartMs),
            weeklyTrend = trend,
            weekTotalMs = weeklyTotals.sum(),
            weekBlocked = trend.sumOf { it.blockedCount },
            weekWalkedAway = trend.sumOf { it.walkedAwayCount }
        )
    }
}
