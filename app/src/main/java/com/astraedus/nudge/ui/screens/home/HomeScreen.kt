package com.astraedus.nudge.ui.screens.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astraedus.nudge.ui.components.StrictModeChallengeHost
import com.astraedus.nudge.ui.screens.stats.charts.BlockedTrendChart
import com.astraedus.nudge.ui.screens.stats.charts.WeeklyBarChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToApps: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToActiveRules: () -> Unit = {},
    onNavigateToWillpower: () -> Unit = {},
    onNavigateToInterventions: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val challenge by viewModel.challenge.collectAsStateWithLifecycle()
    val context = LocalContext.current

    StrictModeChallengeHost(
        challenge = challenge,
        onVerify = viewModel::verifyChallenge,
        onCancel = viewModel::cancelChallenge
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Nudge",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    Switch(
                        checked = state.isGlobalEnabled,
                        onCheckedChange = { viewModel.toggleGlobalEnabled() }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    icon = Icons.Outlined.Schedule,
                    label = "Screen Time",
                    value = if (state.hasUsagePermission) state.todayTotalUsageFormatted else "--",
                    subtitle = if (!state.hasUsagePermission) "Tap to enable" else null,
                    modifier = Modifier.weight(1f),
                    // Granted, this card used to be inert — the one tile showing a number the
                    // stats screen exists to explain, and tapping it did nothing.
                    onClick = if (!state.hasUsagePermission) {
                        {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                            )
                        }
                    } else onNavigateToStats
                )
                StatCard(
                    icon = Icons.Outlined.Shield,
                    label = "Active Apps",
                    value = state.activeRuleCount.toString(),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToActiveRules
                )
            }

            WeekAtAGlanceCard(
                charts = state.charts,
                weekTotalFormatted = state.weekTotalFormatted,
                hasUsagePermission = state.hasUsagePermission,
                onClick = onNavigateToStats
            )

            Text(
                "Today",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    icon = Icons.Outlined.Block,
                    label = "Blocked",
                    value = state.blockedCountToday.toString(),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToInterventions
                )
                StatCard(
                    icon = Icons.Outlined.ThumbUp,
                    label = "Walked Away",
                    value = state.changedMindCountToday.toString(),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToWillpower
                )
            }

            Text(
                "All Time",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    icon = Icons.Outlined.Block,
                    label = "Blocked",
                    value = state.allTimeBlockedCount.toString(),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToInterventions
                )
                StatCard(
                    icon = Icons.Outlined.ThumbUp,
                    label = "Walked Away",
                    value = state.allTimeChangedMindCount.toString(),
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToWillpower
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )

            NavCard(
                icon = Icons.Outlined.Apps,
                title = "Manage Apps",
                subtitle = "Configure block rules per app",
                onClick = onNavigateToApps
            )

            NavCard(
                icon = Icons.Outlined.BarChart,
                title = "Usage Stats",
                subtitle = "See how you spend your time",
                onClick = onNavigateToStats
            )

            NavCard(
                icon = Icons.Outlined.Settings,
                title = "Settings",
                subtitle = "Permissions and preferences",
                onClick = onNavigateToSettings
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The dashboard's two mini charts: screen time and nudges over the last 7 days.
 *
 * Read-only on purpose — the charts take `onSelectDay = null`, so taps fall through to the
 * card and open the full stats screen rather than starting a day-selection interaction the
 * home screen has nowhere to display.
 */
@Composable
private fun WeekAtAGlanceCard(
    charts: HomeCharts,
    weekTotalFormatted: String,
    hasUsagePermission: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Last 7 days",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (hasUsagePermission) weekTotalFormatted else "--",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open usage stats",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (charts.isEmpty) {
                Text(
                    "Your screen time and nudges will chart here once there's a day of data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            WeeklyBarChart(
                days = charts.weeklyScreenTime,
                chartHeight = 64.dp
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Nudges",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${charts.weekBlocked} blocked · ${charts.weekWalkedAway} walked away",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            BlockedTrendChart(
                days = charts.weeklyTrend,
                chartHeight = 56.dp,
                showLegend = false
            )
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun NavCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
