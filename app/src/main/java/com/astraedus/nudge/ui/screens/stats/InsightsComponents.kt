package com.astraedus.nudge.ui.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Chrome shared by the two insight screens (Willpower and Interventions) so their range
 * toggle, section framing and empty states cannot drift apart.
 */

/** 7d / 30d selector. Drives every range-scoped section on a screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsRangeToggle(
    selected: InsightsRange,
    onSelect: (InsightsRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = InsightsRange.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selected,
                onClick = { onSelect(range) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(range.label)
            }
        }
    }
}

/** Titled card wrapper, matching the Usage Stats screen's section styling. */
@Composable
fun InsightSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.padding(top = 8.dp),
                content = content
            )
        }
    }
}

/** The one friendly empty state, so "no data yet" reads the same everywhere. */
@Composable
fun InsightEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 72.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
