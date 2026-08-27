package com.astraedus.nudge.ui.screens.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val durationMs: Long,
    val formattedDuration: String,
    val fraction: Float
)

@Immutable
data class StatsUiState(
    val totalFormatted: String = "0s",
    val appStats: List<AppUsageStat> = emptyList(),
    val weeklyData: List<DayData> = emptyList(),
    val trendData: List<TrendDay> = emptyList(),
    val hourlyMs: List<Long> = List(24) { 0L },
    val streakDays: Int = 0,
    val hasUsagePermission: Boolean = true,
    val isToday: Boolean = true,
    val dateLabel: String = "Today",
    /** Which of the 7 bars is the selected day. Drives chart highlighting. */
    val selectedDayIndex: Int = StatsDaySelection.WINDOW_DAYS - 1,
    /** "Last 7 days", or explicit dates once the user scrolls back. */
    val weekRangeLabel: String = "Last 7 days",
    /** Whether the forward arrow does anything. */
    val canGoForward: Boolean = false,
    /** Screen time across the whole 7-bar window, already formatted. */
    val weekTotalFormatted: String = "0s",
    /** Nudge counts for the SELECTED day — the numbers a tapped bar has to move. */
    val selectedDayBlocked: Int = 0,
    val selectedDayWalkedAway: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val statsCalculator: StatsCalculator
) : ViewModel() {

    private val _selection = MutableStateFlow(StatsDaySelection.startingAt(LocalDate.now()))

    /** The single source of truth for "which day am I looking at". */
    val selection: StateFlow<StatsDaySelection> = _selection.asStateFlow()

    // Name caching lives in InstalledAppsRepository (per-package, off-main-thread).
    private suspend fun resolveAppName(packageName: String): String =
        installedAppsRepository.resolveAppName(packageName)

    fun goToPreviousDay() {
        _selection.value = _selection.value.previousDay()
    }

    fun goToNextDay() {
        _selection.value = _selection.value.nextDay(LocalDate.now())
    }

    /** Called when a bar is tapped. This is what the old per-chart `selectedIndex` never did. */
    fun selectDay(index: Int) {
        _selection.value = _selection.value.selectIndex(index, LocalDate.now())
    }

    fun jumpToToday() {
        _selection.value = _selection.value.jumpToToday(LocalDate.now())
    }

    /**
     * Room events covering the whole displayed window. Keyed on the WINDOW, not the selected
     * day, so tapping between bars re-slices the list already in memory instead of
     * re-subscribing to a new query.
     */
    private val weekEventsFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            val windowStart = weekEnd.minusDays((StatsDaySelection.WINDOW_DAYS - 1).toLong())
            usageRepository.getEventsSince(windowStart.toEpochMs())
        }

    /** 7 daily totals for the bars. Only re-read when the window moves. */
    private val weeklyScreenTimeFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            polled(isLive = weekEnd == LocalDate.now()) {
                screenTimeProvider.getDailyScreenTimesForWeek(weekEnd.toEpochMs())
            }
        }

    /** Total / hourly / per-app for the SELECTED day. Re-read when the selection moves. */
    private val dayScreenTimeFlow = _selection
        .map { it.selected }
        .distinctUntilChanged()
        .flatMapLatest { date ->
            polled(isLive = date == LocalDate.now()) {
                val dayStartMs = date.toEpochMs()
                val dayEndMs = if (date == LocalDate.now()) {
                    System.currentTimeMillis()
                } else {
                    dayStartMs + DAY_MS
                }
                DayScreenTime(
                    totalMs = screenTimeProvider.getTotalScreenTime(dayStartMs, dayEndMs),
                    hourlyMs = screenTimeProvider.getHourlyScreenTime(dayStartMs, dayEndMs),
                    perApp = screenTimeProvider.getPerAppScreenTime(dayStartMs, dayEndMs)
                )
            }
        }

    val uiState: StateFlow<StatsUiState> = combine(
        weekEventsFlow,
        weeklyScreenTimeFlow,
        dayScreenTimeFlow,
        _selection
    ) { weekEvents, weeklyTotals, dayScreenTime, selection ->
        buildUiState(weekEvents, weeklyTotals, dayScreenTime, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private suspend fun buildUiState(
        weekEvents: List<UsageEvent>,
        weeklyTotals: List<Long>,
        dayScreenTime: DayScreenTime,
        selection: StatsDaySelection
    ): StatsUiState {
        val today = LocalDate.now()
        val hasPermission = screenTimeProvider.hasPermission()
        val isToday = selection.isSelectedToday(today)
        val dayStartMs = selection.selected.toEpochMs()
        val dayEndMs = dayStartMs + DAY_MS
        val weekEndStartMs = selection.weekEnd.toEpochMs()

        val byPackage = dayScreenTime.perApp.entries.sortedByDescending { it.value }
        val maxMs = byPackage.maxOfOrNull { it.value } ?: 1L

        val appStats = byPackage
            .filter { it.value > 0L }
            .map { (pkg, ms) ->
                AppUsageStat(
                    packageName = pkg,
                    appName = resolveAppName(pkg),
                    durationMs = ms,
                    formattedDuration = timeTracker.formatDuration(ms),
                    fraction = (ms.toFloat() / maxMs.toFloat()).coerceIn(0.05f, 1f)
                )
            }

        val selectedDayEvents = weekEvents.filter { it.timestamp in dayStartMs until dayEndMs }

        return StatsUiState(
            totalFormatted = formatTotal(dayScreenTime.totalMs, hasPermission),
            appStats = appStats,
            weeklyData = statsCalculator.buildWeeklyDataFromTotals(weeklyTotals, weekEndStartMs),
            trendData = statsCalculator.buildTrendData(weekEvents, weekEndStartMs),
            hourlyMs = dayScreenTime.hourlyMs,
            // Anchored on the window's last day, not the selected one: a streak is a
            // "how am I doing right now" number, and scrubbing back through the week to
            // inspect a Tuesday must not rewrite it.
            streakDays = statsCalculator.calculateStreak(weekEvents, weekEndStartMs),
            hasUsagePermission = hasPermission,
            isToday = isToday,
            dateLabel = StatsDateLabels.day(selection.selected, today),
            selectedDayIndex = selection.selectedIndex,
            weekRangeLabel = StatsDateLabels.range(selection.weekStart, selection.weekEnd, today),
            canGoForward = selection.canGoForward(today),
            weekTotalFormatted = timeTracker.formatDuration(weeklyTotals.sum()),
            selectedDayBlocked = selectedDayEvents.count { it.wasBlocked },
            selectedDayWalkedAway = selectedDayEvents.count { it.userChangedMind }
        )
    }

    private fun formatTotal(ms: Long, hasPermission: Boolean): String =
        if (hasPermission && ms < 60_000L) "< 1m" else timeTracker.formatDuration(ms)

    private data class DayScreenTime(
        val totalMs: Long,
        val hourlyMs: List<Long>,
        val perApp: Map<String, Long>
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val POLL_INTERVAL_MS = 30_000L

        fun LocalDate.toEpochMs(): Long =
            atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun formatDateLabel(date: LocalDate): String = StatsDateLabels.full(date)

        /**
         * `UsageStatsManager` has no Flow, so a live day has to be polled. A window that has
         * already ended cannot change, so it emits once and completes — a phone left on a past
         * day used to keep waking every 30 s to re-read seven identical binder queries.
         *
         * Runs on IO: `getDailyScreenTimesForWeek` is seven binder round-trips and this feeds a
         * `stateIn(viewModelScope)`, i.e. the main thread.
         */
        internal fun <T> polled(isLive: Boolean, produce: suspend () -> T): Flow<T> = flow {
            while (true) {
                emit(produce())
                if (!isLive) break
                delay(POLL_INTERVAL_MS)
            }
        }.flowOn(Dispatchers.IO)
    }
}
