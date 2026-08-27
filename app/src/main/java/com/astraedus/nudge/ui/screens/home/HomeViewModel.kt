package com.astraedus.nudge.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.data.repository.BlockRuleRepository
import com.astraedus.nudge.data.repository.ScreenTimeProvider
import com.astraedus.nudge.data.repository.UsageRepository
import com.astraedus.nudge.domain.engine.TimeTracker
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.ui.lock.StrictModeGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class HomeUiState(
    val isGlobalEnabled: Boolean = true,
    val todayTotalUsageFormatted: String = "0s",
    val activeRuleCount: Int = 0,
    val blockedCountToday: Int = 0,
    val changedMindCountToday: Int = 0,
    val allTimeBlockedCount: Int = 0,
    val allTimeChangedMindCount: Int = 0,
    val hasUsagePermission: Boolean = true,
    /** The two mini charts on the dashboard. */
    val charts: HomeCharts = HomeCharts(),
    val weekTotalFormatted: String = "0s"
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val nudgePreferences: NudgePreferences,
    private val usageRepository: UsageRepository,
    private val blockRuleRepository: BlockRuleRepository,
    private val screenTimeProvider: ScreenTimeProvider,
    private val timeTracker: TimeTracker,
    private val homeChartsBuilder: HomeChartsBuilder
) : ViewModel() {

    private val strictModeGate = StrictModeGate(nudgePreferences)

    /** Active Strict Mode unlock challenge, if a weakening action is pending. */
    val challenge: StateFlow<ChallengeState?> = strictModeGate.challenge

    /**
     * Start-of-today, re-derived on every poll tick rather than once at construction.
     *
     * It used to be a `val` computed in the constructor, so a phone left on the home screen
     * over midnight kept counting yesterday's events under a heading that said "Today" — the
     * same "the label and the numbers disagree" defect as the stats-chart selection bug.
     * `distinctUntilChanged` means the downstream Room queries are still re-subscribed exactly
     * once a day, not every 30 s.
     */
    private val dayStartFlow = flow {
        while (true) {
            emit(timeTracker.startOfToday())
            delay(POLL_INTERVAL_MS)
        }
    }.distinctUntilChanged()

    private val countsFlow = dayStartFlow.flatMapLatest { dayStart ->
        val dayEnd = dayStart + DAY_MS
        combine(
            usageRepository.getBlockedCountForDay(dayStart, dayEnd),
            usageRepository.getChangedMindCountForDay(dayStart, dayEnd),
            usageRepository.getAllTimeBlockedCount(),
            usageRepository.getAllTimeChangedMindCount()
        ) { blockedToday, changedMindToday, allTimeBlocked, allTimeChangedMind ->
            CountsSnapshot(blockedToday, changedMindToday, allTimeBlocked, allTimeChangedMind)
        }
    }

    /**
     * The week of `usage_events` behind the dashboard's trend chart. Same Room table the tiles
     * already observe, one extra windowed query — no new storage and no new writes.
     */
    private val weekEventsFlow = dayStartFlow.flatMapLatest { dayStart ->
        usageRepository.getEventsSince(dayStart - (WEEK_DAYS - 1) * DAY_MS)
    }

    /**
     * `UsageStatsManager` has no Flow, so screen time is polled. On IO: the weekly series is
     * seven binder round-trips and this feeds the main thread via `stateIn`.
     */
    private val screenTimeFlow = flow {
        while (true) {
            emit(
                ScreenTimeSnapshot(
                    hasPermission = screenTimeProvider.hasPermission(),
                    todayMs = screenTimeProvider.getTotalScreenTimeToday(),
                    weeklyTotals = screenTimeProvider.getDailyScreenTimesForWeek()
                )
            )
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<HomeUiState> = combine(
        nudgePreferences.isGlobalEnabled,
        blockRuleRepository.getEnabledRules().map { rules ->
            rules.mapNotNull { it.packageName }.distinct().size
        },
        countsFlow,
        screenTimeFlow,
        weekEventsFlow
    ) { enabled, activeRuleCount, counts, screenTime, weekEvents ->
        val charts = homeChartsBuilder.build(
            weeklyTotals = screenTime.weeklyTotals,
            weekEvents = weekEvents,
            todayStartMs = timeTracker.startOfToday()
        )
        HomeUiState(
            isGlobalEnabled = enabled,
            todayTotalUsageFormatted = if (screenTime.hasPermission && screenTime.todayMs < 60_000L) {
                "< 1m"
            } else {
                timeTracker.formatDuration(screenTime.todayMs)
            },
            activeRuleCount = activeRuleCount,
            blockedCountToday = counts.blockedToday,
            changedMindCountToday = counts.changedMindToday,
            allTimeBlockedCount = counts.allTimeBlocked,
            allTimeChangedMindCount = counts.allTimeChangedMind,
            hasUsagePermission = screenTime.hasPermission,
            charts = charts,
            weekTotalFormatted = timeTracker.formatDuration(charts.weekTotalMs)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private data class CountsSnapshot(
        val blockedToday: Int,
        val changedMindToday: Int,
        val allTimeBlocked: Int,
        val allTimeChangedMind: Int
    )

    private data class ScreenTimeSnapshot(
        val hasPermission: Boolean,
        val todayMs: Long,
        val weeklyTotals: List<Long>
    )

    fun toggleGlobalEnabled() {
        viewModelScope.launch {
            val current = uiState.value.isGlobalEnabled
            // Turning protection ON is free; only ON -> OFF (weakening) is gated by Strict Mode.
            if (current) {
                strictModeGate.run(prompt = "Turn off all blocking") {
                    nudgePreferences.setGlobalEnabled(false)
                }
            } else {
                nudgePreferences.setGlobalEnabled(true)
            }
        }
    }

    /** Called from the challenge dialog; runs the pending weakening action on exact match. */
    fun verifyChallenge(input: String) {
        viewModelScope.launch { strictModeGate.verifyAndRun(input) }
    }

    /** Called when the user cancels the challenge dialog. */
    fun cancelChallenge() {
        strictModeGate.cancel()
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val WEEK_DAYS = 7L
        private const val POLL_INTERVAL_MS = 30_000L
    }
}
