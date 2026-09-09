package com.ewaldmire.osmride.ui.bodymetrics

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val axisDateFormatter = DateTimeFormatter.ofPattern("MMM d")
private const val ROLLING_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/** A single measurement in a trend series - used for both weight and waist, since the chart
 * itself doesn't care what unit the value is in (callers pre-convert for display). */
data class TrendPoint(val epochMillis: Long, val value: Double)

/**
 * Value-vs-time strip: raw daily points as a thin faded line/dots, with a bold 7-day rolling
 * average line and filled area on top - daily weight/waist fluctuates from water, sodium, timing,
 * etc., so the average is the actionable trend, but showing the raw noise too keeps a bad single
 * day from misreading as a real regression.
 */
@Composable
fun TrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    val chronological = remember(points) { points.sortedBy { it.epochMillis } }
    val averaged = remember(chronological) { rollingAverage(chronological, ROLLING_WINDOW_MILLIS) }

    val allValues = chronological.map { it.value } + averaged.map { it.value }
    val minValue = remember(allValues) { allValues.minOrNull() ?: 0.0 }
    val maxValue = remember(allValues) { (allValues.maxOrNull() ?: 1.0).coerceAtLeast(minValue + 0.1) }
    val minTime = chronological.firstOrNull()?.epochMillis ?: 0L
    val maxTime = (chronological.lastOrNull()?.epochMillis ?: (minTime + 1L)).coerceAtLeast(minTime + 1L)

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val fillColor = MaterialTheme.colorScheme.primary
    val rawColor = MaterialTheme.colorScheme.onSurfaceVariant
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
        fun yFor(value: Double) = size.height - ((value - minValue) / (maxValue - minValue)).toFloat() * size.height

        // Raw daily points - thin, faded, no fill, so they read as background noise not signal.
        val rawPath = Path().apply {
            chronological.forEachIndexed { index, point ->
                val x = xFor(point.epochMillis)
                val y = yFor(point.value)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(rawPath, color = rawColor.copy(alpha = 0.35f), style = Stroke(width = 2f))
        chronological.forEach { point ->
            drawCircle(
                color = rawColor.copy(alpha = 0.35f),
                radius = 3f,
                center = Offset(xFor(point.epochMillis), yFor(point.value)),
            )
        }

        // 7-day rolling average - the actual signal, drawn bold with a filled area underneath.
        val avgPath = Path().apply {
            moveTo(0f, size.height)
            averaged.forEach { point -> lineTo(xFor(point.epochMillis), yFor(point.value)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(avgPath, color = fillColor.copy(alpha = 0.5f))
        val avgLine = Path().apply {
            averaged.forEachIndexed { index, point ->
                val x = xFor(point.epochMillis)
                val y = yFor(point.value)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(avgLine, color = fillColor, style = Stroke(width = 5f))

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

/** points must be chronological (oldest first). Each output point is the average of every input
 * point within [windowMillis] before (and including) it - a trailing average, not centered, so
 * it only ever needs data that's already been logged. */
private fun rollingAverage(points: List<TrendPoint>, windowMillis: Long): List<TrendPoint> {
    val result = ArrayList<TrendPoint>(points.size)
    var windowStartIndex = 0
    var windowSum = 0.0
    for (i in points.indices) {
        windowSum += points[i].value
        val cutoff = points[i].epochMillis - windowMillis
        while (points[windowStartIndex].epochMillis <= cutoff) {
            windowSum -= points[windowStartIndex].value
            windowStartIndex++
        }
        val windowCount = i - windowStartIndex + 1
        result.add(TrendPoint(points[i].epochMillis, windowSum / windowCount))
    }
    return result
}
