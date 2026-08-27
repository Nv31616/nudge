package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TrendDay(
    val label: String,
    val blockedCount: Int,
    val walkedAwayCount: Int
)

/**
 * Blocks (bars) against walk-aways (line) over the same 7 days as [WeeklyBarChart].
 *
 * Selection is controlled for the same reason as [WeeklyBarChart]: the highlighted day must be
 * the day the surrounding screen is reporting numbers for. Pass `onSelectDay = null` for a
 * read-only chart.
 */
@Composable
fun BlockedTrendChart(
    days: List<TrendDay>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelectDay: ((Int) -> Unit)? = null,
    chartHeight: Dp = 80.dp,
    showLegend: Boolean = true
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    if (days.isEmpty() || days.all { it.blockedCount == 0 && it.walkedAwayCount == 0 }) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(chartHeight),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "No nudges yet",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )
        }
        return
    }

    val maxCount = days.maxOf { maxOf(it.blockedCount, it.walkedAwayCount) }.coerceAtLeast(1)
    val activeIndex = selectedIndex?.takeIf { it in days.indices }
    // See WeeklyBarChart: keying on the callback would rebuild the gesture detector every
    // recomposition, because a method reference is a fresh object each time.
    val barCount = days.size
    val currentOnSelect by rememberUpdatedState(onSelectDay)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .semantics {
                    contentDescription = days.joinToString(", ") {
                        "${it.label} ${it.blockedCount} blocked ${it.walkedAwayCount} walked away"
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
            val topInset = 8.dp.toPx()
            val plotHeight = size.height - topInset

            days.forEachIndexed { index, day ->
                val x = ChartGeometry.barLeft(index, size.width, days.size, spacing)
                val fraction = (day.blockedCount.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                val barHeight =
                    (plotHeight * fraction).coerceAtLeast(if (day.blockedCount > 0) 3.dp.toPx() else 0f)

                if (barHeight > 0f) {
                    // The selected day's bar is drawn solid; the rest stay as backdrop.
                    val dimmed = activeIndex != null && activeIndex != index
                    drawRoundRect(
                        color = primaryColor.copy(alpha = if (dimmed) 0.15f else 0.45f),
                        topLeft = Offset(x, plotHeight - barHeight + topInset),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }

            if (days.any { it.walkedAwayCount > 0 }) {
                val path = Path()
                val points = days.mapIndexed { index, day ->
                    val x = ChartGeometry.barLeft(index, size.width, days.size, spacing) + barWidth / 2f
                    val fraction = (day.walkedAwayCount.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                    Offset(x, plotHeight - (plotHeight * fraction) + topInset)
                }
                points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }

                drawPath(
                    path = path,
                    color = secondaryColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )

                points.forEachIndexed { index, point ->
                    val dimmed = activeIndex != null && activeIndex != index
                    drawCircle(
                        color = if (dimmed) secondaryColor.copy(alpha = UNSELECTED_ALPHA) else secondaryColor,
                        radius = if (activeIndex == index) 4.dp.toPx() else 3.dp.toPx(),
                        center = point
                    )
                }
            }
        }

        DayAxisLabels(labels = days.map { it.label }, selectedIndex = activeIndex)

        if (showLegend) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendSwatch(color = primaryColor.copy(alpha = 0.45f), label = "Blocked")
                Text("   ", style = MaterialTheme.typography.labelSmall)
                LegendSwatch(color = secondaryColor, label = "Walked Away")
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
    Text(
        " $label",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp
    )
}
