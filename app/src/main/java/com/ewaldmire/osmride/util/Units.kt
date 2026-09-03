package com.ewaldmire.osmride.util

import java.util.Locale

/** Formatting helpers — the app displays imperial units throughout, per how it's used. */
object Units {
    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_FOOT = 0.3048
    private const val MPS_TO_MPH = 2.2369362921

    fun metersToMiles(meters: Double): Double = meters / METERS_PER_MILE
    fun metersToFeet(meters: Double): Double = meters / METERS_PER_FOOT
    fun mpsToMph(metersPerSecond: Double): Double = metersPerSecond * MPS_TO_MPH

    fun formatMiles(meters: Double): String = String.format(Locale.US, "%.2f mi", metersToMiles(meters))
    fun formatFeet(meters: Double): String = String.format(Locale.US, "%.0f ft", metersToFeet(meters))
    fun formatMph(metersPerSecond: Double): String = String.format(Locale.US, "%.1f mph", mpsToMph(metersPerSecond))
    fun formatWatts(watts: Double?): String = if (watts == null) "--" else String.format(Locale.US, "%.0f W", watts)
    fun formatCadence(rpm: Double?): String = if (rpm == null) "--" else String.format(Locale.US, "%.0f rpm", rpm)
    fun formatHeartRate(bpm: Double?): String = if (bpm == null) "--" else String.format(Locale.US, "%.0f bpm", bpm)
    fun formatHeartRate(bpm: Int?): String = if (bpm == null) "--" else "$bpm bpm"

    fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
