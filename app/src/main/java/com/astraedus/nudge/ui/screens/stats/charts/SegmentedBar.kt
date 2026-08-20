package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One slice of a horizontal stacked bar. [weight] is a relative, non-negative magnitude. */
data class BarSegment(val weight: Float, val color: Color)

/**
 * A single horizontal stacked bar (e.g. a block-mode breakdown: hard-block / delay /
 * breathing). Segments are laid out left to right proportional to weight / total weight,
 * inside a rounded-pill silhouette with no gaps between segments. Negative weights are
 * treated as zero. Falls back to a plain track when there is nothing to show.
 */
@Composable
fun SegmentedBar(
    segments: List<BarSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val clamped = segments.map { it.copy(weight = it.weight.coerceAtLeast(0f)) }
    val total = clamped.sumOf { it.weight.toDouble() }.toFloat()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val radius = size.height / 2f
        val pillPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset.Zero, size),
                    cornerRadius = CornerRadius(radius)
                )
            )
        }

        clipPath(pillPath) {
            if (total <= 0f) {
                drawRect(color = trackColor, topLeft = Offset.Zero, size = size)
            } else {
                val minWidthPx = 1.dp.toPx()
                var x = 0f
                clamped.forEach { segment ->
                    if (segment.weight <= 0f) return@forEach
                    val rawWidth = size.width * (segment.weight / total)
                    val segmentWidth = rawWidth.coerceAtLeast(minWidthPx)
                    drawRect(
                        color = segment.color,
                        topLeft = Offset(x, 0f),
                        size = Size(segmentWidth, size.height)
                    )
                    x += segmentWidth
                }
            }
        }
    }
}
