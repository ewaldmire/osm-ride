package com.ewaldmire.osmride.route

import com.ewaldmire.osmride.util.Haversine
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class ParsedGpx(
    val name: String?,
    val points: List<RoutePoint>,
    val totalDistanceMeters: Double,
    val elevationGainMeters: Double,
)

/** Parses a GPX 1.1 file's track (`trkpt`) or route (`rtept`) points using a streaming pull parser. */
object GpxParser {
    private data class RawPoint(val lat: Double, val lon: Double, val elevationMeters: Double?)

    fun parse(input: InputStream): ParsedGpx {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)

        var routeName: String? = null
        val rawPoints = mutableListOf<RawPoint>()

        var currentTag = ""
        var inPoint = false
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        val text = StringBuilder()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    text.clear()
                    if (currentTag == "trkpt" || currentTag == "rtept") {
                        inPoint = true
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        ele = null
                    }
                }
                XmlPullParser.TEXT -> {
                    text.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "ele" -> if (inPoint) ele = text.toString().trim().toDoubleOrNull()
                        "name" -> if (routeName == null) {
                            val trimmed = text.toString().trim()
                            if (trimmed.isNotEmpty()) routeName = trimmed
                        }
                        "trkpt", "rtept" -> {
                            rawPoints.add(RawPoint(lat, lon, ele))
                            inPoint = false
                        }
                    }
                    text.clear()
                }
                else -> Unit
            }
            eventType = parser.next()
        }

        return buildParsedGpx(routeName, rawPoints)
    }

    private fun buildParsedGpx(name: String?, rawPoints: List<RawPoint>): ParsedGpx {
        val points = mutableListOf<RoutePoint>()
        var cumulative = 0.0
        var elevationGain = 0.0
        var previous: RawPoint? = null

        for (raw in rawPoints) {
            previous?.let { prev ->
                cumulative += Haversine.distanceMeters(prev.lat, prev.lon, raw.lat, raw.lon)
                val prevEle = prev.elevationMeters
                val curEle = raw.elevationMeters
                if (prevEle != null && curEle != null && curEle > prevEle) {
                    elevationGain += curEle - prevEle
                }
            }
            points.add(RoutePoint(raw.lat, raw.lon, raw.elevationMeters, cumulative))
            previous = raw
        }

        return ParsedGpx(
            name = name,
            points = points,
            totalDistanceMeters = cumulative,
            elevationGainMeters = elevationGain,
        )
    }
}
