package com.astraedus.nudge.ui.screens.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.screens.stats.charts.BarSegment
import com.astraedus.nudge.ui.screens.stats.charts.RateBar
import com.astraedus.nudge.ui.screens.stats.charts.RateBarChart
import com.astraedus.nudge.ui.screens.stats.charts.SegmentedBar
import com.astraedus.nudge.ui.screens.stats.charts.WalkAwayRing

/**
 * "Your willpower, visualized." Supportive, never shaming — the hero always speaks in
 * terms of what the user DID (walked away), not what they failed to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WillpowerScreen(
    viewModel: WillpowerViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val calculator = viewModel.calculator

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Willpower") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InsightsRangeToggle(
                    selected = state.range,
                    onSelect = viewModel::selectRange,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 4.dp)
                )
            }

            item { WillpowerHero(state.insights) }

            item { TimeReclaimedSection(state) }

            item { WillpowerClockSection(state.insights, calculator) }

            item { ResistanceLeaderboardSection(state.apps) }

            item { WeeklyTrendSection(state.insights, calculator) }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun WillpowerHero(insights: WillpowerInsights, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The supportive line below carries the empty-state wording for this hero, so the
        // ring must not print its own — two "no blocks yet" sentences stacked reads broken.
        WalkAwayRing(
            walkedAway = insights.walkAways,
            gaveIn = insights.gaveIn,
            emptyMessage = ""
        )
        val supportiveLine = if (insights.attempts > 0) {
            "You walked away ${insights.walkAways} of ${insights.attempts} times"
        } else {
            "No blocks yet in this period — that is a clean slate, not a failure."
        }
        Text(
            supportiveLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TimeReclaimedSection(state: WillpowerUiState, modifier: Modifier = Modifier) {
    InsightSection(
        title = "Time Reclaimed",
        subtitle = "Estimated from your average session length in each app.",
        modifier = modifier
    ) {
        if (state.timeReclaimed.totalMs <= 0L) {
            InsightEmptyState("No time reclaimed yet — your walk-aways will show up here.")
        } else {
            Text(
                state.timeReclaimedFormatted,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Time reclaimed (est.)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.timeReclaimed.appsEstimatedFromDefault > 0) {
                Text(
                    "Some apps use a 5-minute default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WillpowerClockSection(
    insights: WillpowerInsights,
    calculator: InsightsCalculator,
    modifier: Modifier = Modifier
) {
    InsightSection(title = "Willpower Clock", modifier = modifier) {
        val maxAttempts = insights.hours.maxOfOrNull { it.attempts } ?: 0
        val bars = insights.hours.map { hour ->
            RateBar(
                label = if (hour.hour % 6 == 0) calculator.hourLabel(hour.hour) else "",
                fraction = hour.rate,
                confidence = if (hour.attempts == 0 || maxAttempts <= 0) {
                    0f
                } else {
                    (hour.attempts.toFloat() / maxAttempts.toFloat()).coerceIn(0f, 1f)
                },
                readout = "${calculator.hourLabel(hour.hour)}: ${hour.walkAways} of " +
                    "${hour.attempts} walked away (${calculator.formatPercent(hour.rate)})"
            )
        }
        RateBarChart(bars = bars, emptyMessage = "No walk-aways yet in this period")

        val strongest = insights.strongestHour
        val weakest = insights.weakestHour
        val callout = when {
            strongest != null && weakest != null ->
                "Strongest at ${calculator.hourLabel(strongest)} · Weakest at ${calculator.hourLabel(weakest)}"
            strongest != null -> "Strongest at ${calculator.hourLabel(strongest)}"
            weakest != null -> "Weakest at ${calculator.hourLabel(weakest)}"
            else -> "Not enough data yet to spot an hourly pattern."
        }
        Text(
            callout,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ResistanceLeaderboardSection(apps: List<WillpowerAppRow>, modifier: Modifier = Modifier) {
    InsightSection(title = "Resistance Leaderboard", modifier = modifier) {
        if (apps.isEmpty()) {
            InsightEmptyState("No blocks yet in this period")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                apps.forEach { row ->
                    AppResistanceRow(row)
                }
            }
        }
    }
}

@Composable
private fun AppResistanceRow(row: WillpowerAppRow, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val icon = row.icon
                if (icon != null) {
                    val bitmap = remember(icon) { icon.toBitmap(32, 32).asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = row.label,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                row.ratePercent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End
            )
        }

        SegmentedBar(
            segments = listOf(
                BarSegment(row.walkAways.toFloat(), MaterialTheme.colorScheme.primary),
                BarSegment(row.gaveIn.toFloat(), MaterialTheme.colorScheme.surfaceVariant)
            )
        )
        Text(
            "${row.walkAways} walked away · ${row.gaveIn} gave in",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WeeklyTrendSection(
    insights: WillpowerInsights,
    calculator: InsightsCalculator,
    modifier: Modifier = Modifier
) {
    InsightSection(title = "Weekly Trend", modifier = modifier) {
        val maxAttempts = insights.weeks.maxOfOrNull { it.attempts } ?: 0
        val bars = insights.weeks.map { week ->
            RateBar(
                label = week.label,
                fraction = week.rate,
                confidence = if (week.attempts == 0 || maxAttempts <= 0) {
                    0f
                } else {
                    (week.attempts.toFloat() / maxAttempts.toFloat()).coerceIn(0f, 1f)
                },
                readout = "${week.label}: ${week.walkAways} of " +
                    "${week.attempts} walked away (${calculator.formatPercent(week.rate)})"
            )
        }
        RateBarChart(bars = bars, emptyMessage = "No data in this period")

        val trendText = if (insights.weeks.size >= 2) {
            val previous = insights.weeks[insights.weeks.size - 2]
            val last = insights.weeks.last()
            if (previous.attempts > 0) {
                val deltaPoints = calculator.percentOf(last.rate) - calculator.percentOf(previous.rate)
                when {
                    deltaPoints > 0 -> "Up $deltaPoints points vs last week"
                    deltaPoints < 0 -> "Down ${-deltaPoints} points vs last week"
                    else -> "Level with last week"
                }
            } else {
                null
            }
        } else {
            "Switch to 30 days to see your weekly trend."
        }
        if (trendText != null) {
            Text(
                trendText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
