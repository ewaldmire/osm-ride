package com.ewaldmire.osmride.ui.ride

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.ewaldmire.osmride.route.Route

private data class ElevationPoint(val distanceMeters: Double, val elevationMeters: Double)

/**
 * Elevation-vs-distance strip along the route, in the same visual language as
 * WorkoutProfileChart: a filled area under the elevation line, with an optional vertical marker
 * for how far along [progressMeters] the rider currently is. Renders nothing if the route has no
 * elevation data (e.g. a GPX with no <ele> tags).
 */
@Composable
fun ElevationProfileChart(route: Route, progressMeters: Double?, modifier: Modifier = Modifier) {
    val points = remember(route.id) {
        route.points.mapNotNull { p -> p.elevationMeters?.let { ElevationPoint(p.cumulativeDistanceMeters, it) } }
    }
    if (points.size < 2) return

    val minElevation = remember(points) { points.minOf { it.elevationMeters } }
    val maxElevation = remember(points) { points.maxOf { it.elevationMeters }.coerceAtLeast(minElevation + 1.0) }
    val totalDistance = route.totalDistanceMeters.coerceAtLeast(1.0)
    val fillColor = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(0f, size.height)
            points.forEach { point ->
                val x = (point.distanceMeters / totalDistance).toFloat() * size.width
                val fraction = ((point.elevationMeters - minElevation) / (maxElevation - minElevation)).toFloat()
                lineTo(x, size.height - fraction * size.height)
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, color = fillColor.copy(alpha = 0.5f))

        if (progressMeters != null) {
            val x = (progressMeters.coerceIn(0.0, totalDistance) / totalDistance).toFloat() * size.width
            drawLine(color = markerColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 5f)
        }
    }
}
