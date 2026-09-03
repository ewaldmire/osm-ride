package com.ewaldmire.osmride.ride

import kotlin.math.roundToInt

/**
 * Parses the plain-text .erg/.mrc interval format used by GoldenCheetah, TrainerRoad, PerfPro
 * etc: a `[COURSE HEADER]`/`[END COURSE HEADER]` metadata block, then `[COURSE DATA]`/
 * `[END COURSE DATA]` containing tab/space-separated `<minutes> <value>` points defining a
 * piecewise-linear power curve (flat holds are just two consecutive points at the same value).
 * .erg point values are absolute watts; .mrc values are a percentage of FTP.
 */
object ErgWorkoutParser {
    fun parse(text: String, isPercentBased: Boolean, ftpWatts: Int?, fallbackName: String): ParsedWorkout {
        var name = fallbackName
        val rawPoints = mutableListOf<Pair<Double, Double>>() // minutes, value (watts or percent)
        var inData = false

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(";")) continue

            if (line.equals("[COURSE DATA]", ignoreCase = true)) {
                inData = true
                continue
            }
            if (line.equals("[END COURSE DATA]", ignoreCase = true)) {
                inData = false
                continue
            }

            if (!inData) {
                val eq = line.indexOf('=')
                if (eq > 0) {
                    val key = line.substring(0, eq).trim().uppercase()
                    val value = line.substring(eq + 1).trim()
                    if ((key == "DESCRIPTION" || key == "FILE NAME") && value.isNotBlank()) {
                        name = value
                    }
                }
                continue
            }

            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val minutes = parts[0].toDoubleOrNull() ?: continue
            val value = parts[1].removeSuffix("%").toDoubleOrNull() ?: continue
            rawPoints.add(minutes to value)
        }

        if (rawPoints.size < 2) {
            return ParsedWorkout(name = name, segments = emptyList(), totalDurationSeconds = 0)
        }

        val segments = mutableListOf<WorkoutSegment>()
        for (i in 0 until rawPoints.size - 1) {
            val (m1, v1) = rawPoints[i]
            val (m2, v2) = rawPoints[i + 1]
            segments.add(
                WorkoutSegment(
                    startSeconds = (m1 * 60).roundToInt().toLong(),
                    endSeconds = (m2 * 60).roundToInt().toLong(),
                    startWatts = toWatts(v1, isPercentBased, ftpWatts),
                    endWatts = toWatts(v2, isPercentBased, ftpWatts),
                ),
            )
        }
        return ParsedWorkout(
            name = name,
            segments = segments,
            totalDurationSeconds = segments.last().endSeconds,
        )
    }

    private fun toWatts(value: Double, isPercentBased: Boolean, ftpWatts: Int?): Int? {
        if (!isPercentBased) return value.roundToInt()
        val ftp = ftpWatts ?: return null
        return (value / 100.0 * ftp).roundToInt()
    }
}
