package com.astraedus.nudge.ui.screens.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val ROWS = 7
private const val COLS = 24
private val ROW_HEIGHT = 14.dp
private val LABEL_COLUMN_WIDTH = 28.dp
private val CELL_GAP = 1.dp

/**
 * 7 rows (weekdays) x 24 columns (hours) of counts. Same visual language as
 * [HourlyHeatmap]: cell color interpolates surfaceVariant -> primary by count / max, with a
 * zero-count cell left at plain surfaceVariant. Guards malformed input ([grid] not 7 rows,
 * or any row not 24 columns) and the all-zero case by rendering [emptyMessage] instead.
 */
@Composable
fun WeekHourHeatmap(
    grid: List<List<Int>>,
    modifier: Modifier = Modifier,
    rowLabels: List<String> = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
    emptyMessage: String = "No blocks yet in this period"
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val isWellFormed = grid.size == ROWS && grid.all { it.size == COLS }
    val hasData = isWellFormed && grid.any { row -> row.any { it > 0 } }

    if (!isWellFormed || !hasData) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT * ROWS),
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

    val maxCount = grid.flatten().max().coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.width(LABEL_COLUMN_WIDTH)) {
                for (rowIndex in 0 until ROWS) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = rowLabels.getOrNull(rowIndex) ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurfaceVariant,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ROW_HEIGHT * ROWS)
            ) {
                val gapPx = CELL_GAP.toPx()
                val cellWidth = (size.width - (COLS - 1) * gapPx) / COLS
                val cellHeight = (size.height - (ROWS - 1) * gapPx) / ROWS

                grid.forEachIndexed { rowIndex, row ->
                    row.forEachIndexed { colIndex, count ->
                        val x = colIndex * (cellWidth + gapPx)
                        val y = rowIndex * (cellHeight + gapPx)
                        val intensity = (count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                        val color = if (count <= 0) {
                            surfaceVariantColor
                        } else {
                            lerp(surfaceVariantColor, primaryColor, intensity)
                        }

                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(cellWidth, cellHeight),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, start = LABEL_COLUMN_WIDTH),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("12am", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
            Text("6am", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
            Text("12pm", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
            Text("6pm", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant, fontSize = 9.sp)
        }
    }
}
