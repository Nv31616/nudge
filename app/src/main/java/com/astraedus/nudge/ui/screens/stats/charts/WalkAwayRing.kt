package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A large donut showing the walk-away rate: the share of blocks where the user tapped
 * "I changed my mind" instead of waiting out the delay. The ring animates in on each
 * composition. When there is no data yet ([walkedAway] + [gaveIn] == 0) only the track
 * is drawn, the center shows "--", and [emptyMessage] is shown beneath the ring.
 */
@Composable
fun WalkAwayRing(
    walkedAway: Int,
    gaveIn: Int,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No blocks yet in this period"
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val total = walkedAway + gaveIn
    val hasData = total > 0
    val fraction = if (hasData) walkedAway.toFloat() / total.coerceAtLeast(1).toFloat() else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 600),
        label = "walkAwayRingSweep"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(180.dp)) {
                val strokeWidth = 20.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f
                )
                val arcSize = Size(diameter, diameter)

                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                if (hasData) {
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animatedFraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (hasData) "${(fraction * 100).roundToInt()}%" else "--",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = onSurfaceColor
                )
                Text(
                    text = "walked away",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant
                )
            }
        }

        // A blank message means the caller renders its own empty-state copy below the ring.
        if (!hasData && emptyMessage.isNotBlank()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
