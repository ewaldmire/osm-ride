package com.ewaldmire.osmride.ui.weight

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.ewaldmire.osmride.weight.WeightEntry

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
    }
}
