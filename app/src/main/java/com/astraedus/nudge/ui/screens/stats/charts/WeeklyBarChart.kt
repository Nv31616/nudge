package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DayData(
    val label: String,
    val totalMs: Long
)

/**
 * 7-day screen-time bars.
 *
 * **Selection is controlled, deliberately.** This chart used to own a private
 * `selectedIndex`, which meant tapping a bar highlighted it and changed nothing else on the
 * screen. [selectedIndex] now comes from the caller's day state and [onSelectDay] pushes taps
 * back into it, so the highlighted bar is by construction the day the rest of the screen is
 * describing.
 *
 * Pass `onSelectDay = null` for a read-only chart (the home dashboard): no tap handling is
 * installed at all, so taps fall through to whatever the chart is nested in.
 */
@Composable
fun WeeklyBarChart(
    days: List<DayData>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelectDay: ((Int) -> Unit)? = null,
    chartHeight: Dp = 100.dp,
    formatDuration: (Long) -> String = ::formatShortDuration
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (days.isEmpty() || days.all { it.totalMs == 0L }) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(chartHeight),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No data this week",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )
        }
        return
    }

    val maxMs = days.maxOf { it.totalMs }.coerceAtLeast(1L)
    val activeIndex = selectedIndex?.takeIf { it in days.indices }
    // `viewModel::selectDay` is a fresh object on every recomposition, so keying the gesture
    // detector on the callback would tear it down and rebuild it each frame the state changes
    // — including mid-tap. Key on the only thing the hit-test actually reads (the bar count)
    // and route through the latest callback.
    val barCount = days.size
    val currentOnSelect by rememberUpdatedState(onSelectDay)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .semantics {
                    contentDescription = days.joinToString(", ") {
                        "${it.label} ${formatDuration(it.totalMs)}"
                    }
                }
                .then(
                    if (onSelectDay == null) Modifier else Modifier.pointerInput(barCount) {
                        detectTapGestures { offset ->
                            val index = ChartGeometry.barIndexAt(
                                x = offset.x,
                                totalWidth = size.width.toFloat(),
                                barCount = barCount,
                                spacing = BAR_SPACING.toPx()
                            )
                            if (index >= 0) currentOnSelect?.invoke(index)
                        }
                    }
                )
        ) {
            val spacing = BAR_SPACING.toPx()
            val barWidth = ChartGeometry.barWidth(size.width, days.size, spacing)
            val canvasHeight = size.height

            days.forEachIndexed { index, day ->
                val x = ChartGeometry.barLeft(index, size.width, days.size, spacing)
                val fraction = (day.totalMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
                val barHeight =
                    (canvasHeight * fraction).coerceAtLeast(if (day.totalMs > 0) 4.dp.toPx() else 0f)

                drawRoundRect(
                    color = surfaceVariantColor,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, canvasHeight),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )

                if (barHeight > 0f) {
                    val dimmed = activeIndex != null && activeIndex != index
                    drawRoundRect(
                        color = if (dimmed) primaryColor.copy(alpha = UNSELECTED_ALPHA) else primaryColor,
                        topLeft = Offset(x, canvasHeight - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(6.dp.toPx())
                    )
                }
            }
        }

        DayAxisLabels(
            labels = days.map { it.label },
            selectedIndex = activeIndex
        )
    }
}

/**
 * The day labels under a 7-bar chart, with a dot marking the selected day.
 *
 * Shared by [WeeklyBarChart] and [BlockedTrendChart] so the two charts on the same screen can
 * never mark different days as selected.
 */
@Composable
internal fun DayAxisLabels(
    labels: List<String>,
    selectedIndex: Int?,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Each label cell is `weight(1f)` inside the SAME inter-bar spacing the Canvas uses, so a
    // label sits under its own bar. `SpaceBetween` (what this was) gives cells of `width/count`
    // against bars of `(width - totalSpacing)/count` — the two drift apart across the row and
    // the selection dot ends up pointing between two bars.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(BAR_SPACING)
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) primaryColor else onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                // A colour change alone is not an affordance on a dim screen; the dot is the
                // unambiguous "this is the day you picked" marker.
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .padding(top = 2.dp)
                ) {
                    if (isSelected) {
                        Surface(
                            modifier = Modifier.size(4.dp),
                            shape = CircleShape,
                            color = primaryColor
                        ) {}
                    }
                }
            }
        }
    }
}

internal fun formatShortDuration(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return when {
        h > 0L -> "${h}h ${m}m"
        m > 0L -> "${m}m"
        else -> "< 1m"
    }
}

internal val BAR_SPACING = 8.dp

/** Bars for days other than the selected one. Dim enough to read as context, not as data. */
internal const val UNSELECTED_ALPHA = 0.35f
