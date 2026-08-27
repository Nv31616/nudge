package com.astraedus.nudge.ui.screens.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.polled
import com.astraedus.nudge.ui.screens.stats.StatsViewModel.Companion.toEpochMs
import com.astraedus.nudge.ui.screens.stats.charts.DayData
import com.astraedus.nudge.ui.screens.stats.charts.TrendDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject

@Immutable
data class AppDetailUiState(
    val packageName: String = "",
    val appName: String = "",
    val todayFormatted: String = "0s",
    val weeklyData: List<DayData> = emptyList(),
    val hourlyMs: List<Long> = List(24) { 0L },
    val trendData: List<TrendDay> = emptyList(),
    val blockedCountToday: Int = 0,
    val walkedAwayCountToday: Int = 0,
    val blockedCountTotal: Int = 0,
    val walkedAwayCountTotal: Int = 0,
    val blockModeBreakdown: Map<String, Int> = emptyMap(),
    val isToday: Boolean = true,
    val dateLabel: String = "Today",
    val selectedDayIndex: Int = StatsDaySelection.WINDOW_DAYS - 1,
    val weekRangeLabel: String = "Last 7 days",
    val canGoForward: Boolean = false,
    val weekTotalFormatted: String = "0s"
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val statsCalculator: StatsCalculator
) : ViewModel() {

    private val packageName: String = savedStateHandle.get<String>("packageName") ?: ""

    private val _selection = MutableStateFlow(StatsDaySelection.startingAt(LocalDate.now()))
    val selection: StateFlow<StatsDaySelection> = _selection

    // Resolved lazily off the main thread on first build; cached in the repo.
    private suspend fun appName(): String =
        installedAppsRepository.resolveAppName(packageName)

    fun goToPreviousDay() {
        _selection.value = _selection.value.previousDay()
    }

    fun goToNextDay() {
        _selection.value = _selection.value.nextDay(LocalDate.now())
    }

    fun selectDay(index: Int) {
        _selection.value = _selection.value.selectIndex(index, LocalDate.now())
    }

    fun jumpToToday() {
        _selection.value = _selection.value.jumpToToday(LocalDate.now())
    }

    private val weekEventsFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            val windowStart = weekEnd.minusDays((StatsDaySelection.WINDOW_DAYS - 1).toLong())
            usageRepository.getEventsSince(windowStart.toEpochMs())
        }

    private val allEventsFlow = usageRepository.getEventsSince(0L)

    private val weeklyScreenTimeFlow = _selection
        .map { it.weekEnd }
        .distinctUntilChanged()
        .flatMapLatest { weekEnd ->
            polled(isLive = weekEnd == LocalDate.now()) {
                screenTimeProvider.getPerAppDailyScreenTimesForWeek(packageName, weekEnd.toEpochMs())
            }
        }

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
                AppDayScreenTime(
                    dayMs = screenTimeProvider.getPerAppScreenTime(dayStartMs, dayEndMs)
                        .getOrDefault(packageName, 0L),
                    hourlyMs = screenTimeProvider.getPerAppHourlyScreenTime(packageName, dayStartMs, dayEndMs)
                )
            }
        }

    /**
     * Five sources is `combine`'s typed limit, so the two screen-time reads are paired first.
     * They are separate flows because the weekly series only changes when the WINDOW moves
     * while the day series changes on every bar tap.
     */
    private val screenTimeFlow = combine(weeklyScreenTimeFlow, dayScreenTimeFlow) { weekly, day ->
        weekly to day
    }

    val uiState: StateFlow<AppDetailUiState> = combine(
        weekEventsFlow,
        allEventsFlow,
        screenTimeFlow,
        _selection
    ) { weekEvents, allEvents, (weeklyTotals, dayScreenTime), selection ->
        buildUiState(weekEvents, allEvents, weeklyTotals, dayScreenTime, selection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppDetailUiState())

    private suspend fun buildUiState(
        weekEvents: List<UsageEvent>,
        allEvents: List<UsageEvent>,
        weeklyTotals: List<Long>,
        dayScreenTime: AppDayScreenTime,
        selection: StatsDaySelection
    ): AppDetailUiState {
        val today = LocalDate.now()
        val isToday = selection.isSelectedToday(today)
        val dayStartMs = selection.selected.toEpochMs()
        val dayEndMs = dayStartMs + DAY_MS
        val weekEndStartMs = selection.weekEnd.toEpochMs()

        val appWeekEvents = weekEvents.filter { it.packageName == packageName }
        val appAllEvents = allEvents.filter { it.packageName == packageName }
        val selectedDayEvents = appWeekEvents.filter { it.timestamp in dayStartMs until dayEndMs }

        val todayFormatted = if (dayScreenTime.dayMs in 1L until 60_000L) {
            "< 1m"
        } else {
            timeTracker.formatDuration(dayScreenTime.dayMs)
        }

        val modeBreakdown = appAllEvents
            .filter { it.wasBlocked && it.blockMode != null }
            .groupBy { it.blockMode!! }
            .mapValues { it.value.size }

        return AppDetailUiState(
            packageName = packageName,
            appName = appName(),
            todayFormatted = todayFormatted,
            weeklyData = statsCalculator.buildWeeklyDataFromTotals(weeklyTotals, weekEndStartMs),
            hourlyMs = dayScreenTime.hourlyMs,
            trendData = statsCalculator.buildAppTrendData(weekEvents, packageName, weekEndStartMs),
            blockedCountToday = selectedDayEvents.count { it.wasBlocked },
            walkedAwayCountToday = selectedDayEvents.count { it.userChangedMind },
            blockedCountTotal = appAllEvents.count { it.wasBlocked },
            walkedAwayCountTotal = appAllEvents.count { it.userChangedMind },
            blockModeBreakdown = modeBreakdown,
            isToday = isToday,
            dateLabel = StatsDateLabels.day(selection.selected, today),
            selectedDayIndex = selection.selectedIndex,
            weekRangeLabel = StatsDateLabels.range(selection.weekStart, selection.weekEnd, today),
            canGoForward = selection.canGoForward(today),
            weekTotalFormatted = timeTracker.formatDuration(weeklyTotals.sum())
        )
    }

    private data class AppDayScreenTime(
        val dayMs: Long,
        val hourlyMs: List<Long>
    )

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
