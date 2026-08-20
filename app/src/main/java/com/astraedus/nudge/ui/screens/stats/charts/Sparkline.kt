package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A minimal trend line -- polyline through [values] with a soft filled area beneath it and
 * a dot on the last point. No axes, no labels, no interaction. Degrades to a flat baseline
 * when there are fewer than 2 points or every value is zero, so a brand-new series never
 * divides by zero or renders a spike out of noise.
 */
@Composable
fun Sparkline(
    values: List<Int>,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val maxValue = values.maxOrNull() ?: 0

        if (values.size < 2 || maxValue <= 0) {
            val y = size.height / 2f
            drawLine(
                color = trackColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            return@Canvas
        }

        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { index, value ->
            val x = index * stepX
            val fraction = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
            val y = size.height - (size.height * fraction)
            Offset(x, y)
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }

        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }

        drawPath(path = fillPath, color = primaryColor.copy(alpha = 0.15f))
        drawPath(
            path = linePath,
            color = primaryColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(color = primaryColor, radius = 3.5.dp.toPx(), center = points.last())
    }
}
