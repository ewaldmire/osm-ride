package com.ewaldmire.osmride.ride

import java.time.Instant
import kotlin.math.roundToInt

/**
 * Writes a standard GPX 1.1 track with the Garmin `TrackPointExtension` (heart rate/cadence)
 * schema that Strava and most other fitness apps parse on GPX import.
 */
object GpxWriter {
    fun write(rideName: String, points: List<RecordedTrackPoint>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<gpx version=\"1.1\" creator=\"OSM Ride\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n",
        )
        sb.append("  <trk>\n")
        sb.append("    <name>${escapeXml(rideName)}</name>\n")
        sb.append("    <trkseg>\n")
        for (p in points) {
            sb.append("      <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">\n")
            p.elevationMeters?.let { sb.append("        <ele>$it</ele>\n") }
            sb.append("        <time>${Instant.ofEpochMilli(p.timestampMillis)}</time>\n")
            if (p.heartRateBpm != null || p.cadenceRpm != null) {
                sb.append("        <extensions>\n")
                sb.append("          <gpxtpx:TrackPointExtension>\n")
                p.heartRateBpm?.let { sb.append("            <gpxtpx:hr>$it</gpxtpx:hr>\n") }
                p.cadenceRpm?.let { sb.append("            <gpxtpx:cad>${it.roundToInt()}</gpxtpx:cad>\n") }
                sb.append("          </gpxtpx:TrackPointExtension>\n")
                sb.append("        </extensions>\n")
            }
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
