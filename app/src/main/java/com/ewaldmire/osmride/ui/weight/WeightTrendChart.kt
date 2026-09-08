package com.ewaldmire.osmride.ui.weight

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.ewaldmire.osmride.weight.WeightEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val axisDateFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * Weight-vs-time strip, same visual language as ElevationProfileChart/WorkoutProfileChart: a
 * filled area under the line. Weigh-ins are sparse (at most a few a week) compared to a route's
 * elevation samples, so a dot at each entry helps the trend read clearly even with few points.
 */
@Composable
fun WeightTrendChart(entries: List<WeightEntry>, modifier: Modifier = Modifier) {
    // entries is newest-first (matches the repository/list); the chart reads left-to-right
    // chronologically, so it needs the reverse.
    val chronological = remember(entries) { entries.sortedBy { it.recordedAtEpochMillis } }

    val minWeight = remember(chronological) { chronological.minOfOrNull { it.weightKg } ?: 0.0 }
    val maxWeight = remember(chronological) {
        (chronological.maxOfOrNull { it.weightKg } ?: 1.0).coerceAtLeast(minWeight + 0.1)
    }
    val minTime = chronological.firstOrNull()?.recordedAtEpochMillis ?: 0L
    val maxTime = (chronological.lastOrNull()?.recordedAtEpochMillis ?: (minTime + 1L)).coerceAtLeast(minTime + 1L)

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelSizePx = with(LocalDensity.current) { 12.sp.toPx() }

    val startLabel = remember(minTime) {
        Instant.ofEpochMilli(minTime).atZone(ZoneId.systemDefault()).format(axisDateFormatter)
    }
    val endLabel = remember(maxTime) {
        Instant.ofEpochMilli(maxTime).atZone(ZoneId.systemDefault()).format(axisDateFormatter)
    }

    Canvas(modifier = modifier) {
        drawRect(color = backgroundColor)

        if (chronological.size < 2) return@Canvas

        fun xFor(epochMillis: Long) = ((epochMillis - minTime).toFloat() / (maxTime - minTime).toFloat()) * size.width
        fun yFor(weightKg: Double) =
            size.height - ((weightKg - minWeight) / (maxWeight - minWeight)).toFloat() * size.height

        val path = Path().apply {
            moveTo(0f, size.height)
            chronological.forEach { entry -> lineTo(xFor(entry.recordedAtEpochMillis), yFor(entry.weightKg)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, color = fillColor.copy(alpha = 0.5f))

        chronological.forEach { entry ->
            val center = Offset(xFor(entry.recordedAtEpochMillis), yFor(entry.weightKg))
            drawCircle(color = dotColor, radius = 5f, center = center)
        }

        // Without these, the line's x-position was scaled by real elapsed time but nothing on
        // screen actually told the viewer that - it just looked like an arbitrary shape.
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                color = labelColor.toArgb()
                textSize = labelSizePx
                isAntiAlias = true
            }
            canvas.nativeCanvas.drawText(startLabel, 4f, size.height - 6f, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(endLabel, size.width - 4f, size.height - 6f, paint)
        }
    }
}
