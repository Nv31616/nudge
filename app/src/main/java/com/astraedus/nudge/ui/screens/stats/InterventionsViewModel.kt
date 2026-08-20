package com.astraedus.nudge.ui.screens.stats

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.db.entity.UsageEvent
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.data.repository.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class InterventionAppRow(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val total: Int,
    val byMode: Map<String, Int>
)

@Immutable
data class InterventionsUiState(
    val range: InsightsRange = InsightsRange.THIRTY_DAYS,
    val insights: InterventionInsights = InterventionInsights(),
    val allTimeTotal: Int = 0,
    val dailyCounts: List<Int> = emptyList(),
    val apps: List<InterventionAppRow> = emptyList(),
    val isLoading: Boolean = true
)

/**
 * Backs the "Interventions" screen — temptation-pattern insights (when/where/how the user
 * gets blocked), as opposed to [StatsViewModel]'s raw screen-time view.
 */
@HiltViewModel
class InterventionsViewModel @Inject constructor(
    private val usageRepository: UsageRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    val calculator: InsightsCalculator
) : ViewModel() {

    private val _range = MutableStateFlow(InsightsRange.THIRTY_DAYS)

    // Widest window the screen ever needs. Loaded ONCE — the range toggle re-slices this
    // same list via the calculator rather than re-querying Room, so flipping 7d/30d never
    // touches the DB.
    private val windowStartMs: Long = calculator.rangeStartMs(
        System.currentTimeMillis(),
        ZoneId.systemDefault(),
        InsightsRange.THIRTY_DAYS
    )

    private val eventsFlow = usageRepository.getEventsSince(windowStartMs)

    private val allTimeCountsFlow = combine(
        usageRepository.getAllTimeBlockedCount(),
        usageRepository.getAllTimeChangedMindCount()
    ) { blocked, changedMind -> blocked to changedMind }

    val uiState: StateFlow<InterventionsUiState> = combine(
        eventsFlow,
        _range,
        allTimeCountsFlow
    ) { events, range, (allTimeBlocked, allTimeChangedMind) ->
        buildUiState(events, range, allTimeBlocked, allTimeChangedMind)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InterventionsUiState())

    fun selectRange(range: InsightsRange) {
        _range.value = range
    }

    private suspend fun buildUiState(
        events: List<UsageEvent>,
        range: InsightsRange,
        allTimeBlocked: Int,
        allTimeChangedMind: Int
    ): InterventionsUiState {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val insights = calculator.interventions(events, now, zone, range)

        // The raw all-time blocked count double-counts every walk-away (it carries
        // wasBlocked=true too) — overlaysFromAllTimeCounts is the one place that correction
        // happens, so the hero "all time" number is never a raw DAO read.
        val allTimeTotal = calculator.overlaysFromAllTimeCounts(allTimeBlocked, allTimeChangedMind)

        val appRows = insights.apps.take(TOP_APPS_LIMIT).map { stat ->
            InterventionAppRow(
                packageName = stat.packageName,
                label = calculator.appDisplayLabel(
                    stat.packageName,
                    installedAppsRepository.resolveAppName(stat.packageName)
                ),
                icon = installedAppsRepository.resolveIcon(stat.packageName),
                total = stat.total,
                byMode = stat.byMode
            )
        }

        return InterventionsUiState(
            range = range,
            insights = insights,
            allTimeTotal = allTimeTotal,
            dailyCounts = insights.dailySeries.map { it.count },
            apps = appRows,
            isLoading = false
        )
    }

    companion object {
        private const val TOP_APPS_LIMIT = 8
    }
}
