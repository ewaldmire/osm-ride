package com.ewaldmire.osmride.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.ewaldmire.osmride.ride.Workout

/**
 * The classic "workout graph" from TrainerRoad/Zwift/GoldenCheetah: one filled block per
 * segment, width = duration, height = power (ramps get a sloped top, flats a flat one). Segments
 * with no fixed power (free ride/max effort) are left as gaps in the baseline. An optional
 * vertical marker shows how far into the workout [progressSeconds] is.
 */
@Composable
fun WorkoutProfileChart(workout: Workout, modifier: Modifier = Modifier, progressSeconds: Long? = null) {
    val maxWatts = remember(workout) {
        workout.segments.flatMap { listOfNotNull(it.startWatts, it.endWatts) }.maxOrNull()?.coerceAtLeast(1) ?: 1
    }
    val totalSeconds = workout.totalDurationSeconds.coerceAtLeast(1)
    val fillColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        drawRect(color = baselineColor)

        for (seg in workout.segments) {
            val startWatts = seg.startWatts ?: continue
            val endWatts = seg.endWatts ?: continue
            val x1 = (seg.startSeconds.toFloat() / totalSeconds) * size.width
            val x2 = (seg.endSeconds.toFloat() / totalSeconds) * size.width
            val y1 = size.height - (startWatts.toFloat() / maxWatts) * size.height
            val y2 = size.height - (endWatts.toFloat() / maxWatts) * size.height
            val path = Path().apply {
                moveTo(x1, size.height)
                lineTo(x1, y1)
                lineTo(x2, y2)
                lineTo(x2, size.height)
                close()
            }
            drawPath(path, color = fillColor)
        }

        if (progressSeconds != null) {
            val x = (progressSeconds.toFloat().coerceIn(0f, totalSeconds.toFloat()) / totalSeconds) * size.width
            drawLine(
                color = markerColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 5f,
            )
        }
    }
}
