package com.ewaldmire.osmride.ride

import java.io.InputStream
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Parses Zwift .zwo structured workout XML. Power attributes (Power/PowerLow/PowerHigh/
 * OnPower/OffPower) are fractions of FTP (e.g. "0.75" = 75%); Duration attributes are seconds.
 * MaxEffort and power-less FreeRide blocks become segments with null watts - no ERG target is
 * sent to the trainer for those stretches.
 */
object ZwoWorkoutParser {
    fun parse(input: InputStream, ftpWatts: Int?, fallbackName: String): ParsedWorkout {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)

        var name: String? = null
        var inNameTag = false
        val nameText = StringBuilder()
        val segments = mutableListOf<WorkoutSegment>()
        var cursorSeconds = 0L

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "name" -> {
                            inNameTag = true
                            nameText.clear()
                        }
                        "Warmup", "Cooldown", "SteadyState", "Ramp" -> {
                            cursorSeconds = addRampOrSteady(parser, segments, cursorSeconds, ftpWatts)
                        }
                        "FreeRide" -> {
                            val duration = attrDouble(parser, "Duration") ?: 0.0
                            val watts = attrFraction(parser, "Power")?.let { toWatts(it, ftpWatts) }
                            val end = cursorSeconds + duration.roundToInt()
                            segments.add(WorkoutSegment(cursorSeconds, end, watts, watts))
                            cursorSeconds = end
                        }
                        "MaxEffort" -> {
                            val duration = attrDouble(parser, "Duration") ?: 0.0
                            val end = cursorSeconds + duration.roundToInt()
                            segments.add(WorkoutSegment(cursorSeconds, end, null, null))
                            cursorSeconds = end
                        }
                        "IntervalsT" -> {
                            val repeatCount = attrDouble(parser, "Repeat")?.roundToInt() ?: 1
                            val onDuration = attrDouble(parser, "OnDuration") ?: 0.0
                            val offDuration = attrDouble(parser, "OffDuration") ?: 0.0
                            val onWatts = attrFraction(parser, "OnPower")?.let { toWatts(it, ftpWatts) }
                            val offWatts = attrFraction(parser, "OffPower")?.let { toWatts(it, ftpWatts) }
                            repeat(repeatCount) {
                                val onEnd = cursorSeconds + onDuration.roundToInt()
                                segments.add(WorkoutSegment(cursorSeconds, onEnd, onWatts, onWatts))
                                cursorSeconds = onEnd
                                val offEnd = cursorSeconds + offDuration.roundToInt()
                                segments.add(WorkoutSegment(cursorSeconds, offEnd, offWatts, offWatts))
                                cursorSeconds = offEnd
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inNameTag) nameText.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "name") {
                        inNameTag = false
                        val trimmed = nameText.toString().trim()
                        if (trimmed.isNotEmpty()) name = trimmed
                    }
                }
                else -> Unit
            }
            eventType = parser.next()
        }

        return ParsedWorkout(
            name = name?.takeIf { it.isNotBlank() } ?: fallbackName,
            segments = segments,
            totalDurationSeconds = cursorSeconds,
        )
    }

    private fun addRampOrSteady(
        parser: XmlPullParser,
        segments: MutableList<WorkoutSegment>,
        cursorSeconds: Long,
        ftpWatts: Int?,
    ): Long {
        val duration = attrDouble(parser, "Duration") ?: 0.0
        val low = attrFraction(parser, "PowerLow")
        val high = attrFraction(parser, "PowerHigh")
        val flat = attrFraction(parser, "Power")
        val startWatts = (low ?: flat)?.let { toWatts(it, ftpWatts) }
        val endWatts = (high ?: flat)?.let { toWatts(it, ftpWatts) }
        val end = cursorSeconds + duration.roundToInt()
        segments.add(WorkoutSegment(cursorSeconds, end, startWatts, endWatts))
        return end
    }

    private fun attrDouble(parser: XmlPullParser, attr: String): Double? =
        parser.getAttributeValue(null, attr)?.toDoubleOrNull()

    private fun attrFraction(parser: XmlPullParser, attr: String): Double? =
        parser.getAttributeValue(null, attr)?.toDoubleOrNull()

    private fun toWatts(ftpFraction: Double, ftpWatts: Int?): Int? {
        val ftp = ftpWatts ?: return null
        return (ftpFraction * ftp).roundToInt()
    }
}
