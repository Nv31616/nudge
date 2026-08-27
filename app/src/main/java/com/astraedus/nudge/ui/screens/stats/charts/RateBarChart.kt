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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One bar. [fraction] is the bar HEIGHT (0f..1f, already normalised by the caller),
 * [confidence] drives the bar's alpha so low-sample bars visibly recede, and [readout]
 * is the sentence shown above the chart when the bar is tapped.
 */
data class RateBar(
    val label: String,
    val fraction: Float,
    val confidence: Float = 1f,
    val readout: String = ""
)

/**
 * Vertical bar chart for rate-style series (e.g. walk-away rate by hour, by app, by rule).
 * Renders 4 bars just as comfortably as 24 -- spacing shrinks automatically as the bar count
 * grows. Tap a bar to pin its [RateBar.readout] above the chart; tap again to clear. Empty
 * when [bars] is empty or every fraction is non-positive.
 */
@Composable
fun RateBarChart(
    bars: List<RateBar>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 96.dp,
    emptyMessage: String = "No data in this period"
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val hasData = bars.isNotEmpty() && bars.any { it.fraction > 0f }

    if (!hasData) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(barHeight),
            contentAlignment = Alignment.Center
        ) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant
            )
        }
        return
    }

    var selectedIndex by remember(bars) { mutableStateOf<Int?>(null) }
    val spacing = if (bars.size > 12) 2.dp else 8.dp

    Column(modifier = modifier.fillMaxWidth()) {
        val selected = selectedIndex?.let { bars.getOrNull(it) }
        if (selected != null && selected.readout.isNotBlank()) {
            Text(
                selected.readout,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = primaryColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .pointerInput(bars, spacing) {
                    detectTapGestures { offset ->
                        val index = ChartGeometry.barIndexAt(
                            x = offset.x,
                            totalWidth = size.width.toFloat(),
                            barCount = bars.size,
                            spacing = spacing.toPx()
                        )
                        if (index >= 0) {
                            selectedIndex = if (selectedIndex == index) null else index
                        }
                    }
                }
        ) {
            val spacingPx = spacing.toPx()
            val barWidth = ChartGeometry.barWidth(size.width, bars.size, spacingPx)

            bars.forEachIndexed { index, bar ->
                val clampedFraction = bar.fraction.coerceIn(0f, 1f)
                if (clampedFraction <= 0f) return@forEachIndexed

                val x = ChartGeometry.barLeft(index, size.width, bars.size, spacingPx)
                val barPixelHeight = (size.height * clampedFraction).coerceAtLeast(3.dp.toPx())
                val baseAlpha = 0.35f + 0.65f * bar.confidence.coerceIn(0f, 1f)
                val alpha = if (selectedIndex != null && selectedIndex != index) {
                    baseAlpha * 0.5f
                } else {
                    baseAlpha
                }

                drawRoundRect(
                    color = primaryColor.copy(alpha = alpha),
                    topLeft = Offset(x, size.height - barPixelHeight),
                    size = Size(barWidth, barPixelHeight),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }

        // The label cells must use the SAME inter-bar spacing as the Canvas, otherwise each
        // cell is `width / count` while each bar is `(width - totalSpacing) / count`, and
        // the two drift apart by up to the full spacing budget across a 24-bar chart —
        // "6am" would sit visibly to the left of the 6am bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            bars.forEach { bar ->
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariant,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
