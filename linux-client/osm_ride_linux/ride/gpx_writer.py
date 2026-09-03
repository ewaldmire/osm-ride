"""Writes a standard GPX 1.1 track with the Garmin TrackPointExtension (heart rate/cadence)
schema that Strava and most other fitness apps parse on GPX import.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/GpxWriter.kt.
"""

from __future__ import annotations

from datetime import datetime, timezone

from .models import RecordedTrackPoint


def write(ride_name: str, points: list[RecordedTrackPoint]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gpx version="1.1" creator="OSM Ride" '
        'xmlns="http://www.topografix.com/GPX/1/1" '
        'xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">',
        "  <trk>",
        f"    <name>{_escape_xml(ride_name)}</name>",
        "    <trkseg>",
    ]
    for p in points:
        lines.append(f'      <trkpt lat="{p.lat}" lon="{p.lon}">')
        if p.elevation_meters is not None:
            lines.append(f"        <ele>{p.elevation_meters}</ele>")
        lines.append(f"        <time>{_format_time(p.timestamp)}</time>")
        if p.heart_rate_bpm is not None or p.cadence_rpm is not None:
            lines.append("        <extensions>")
            lines.append("          <gpxtpx:TrackPointExtension>")
            if p.heart_rate_bpm is not None:
                lines.append(f"            <gpxtpx:hr>{p.heart_rate_bpm}</gpxtpx:hr>")
            if p.cadence_rpm is not None:
                lines.append(f"            <gpxtpx:cad>{round(p.cadence_rpm)}</gpxtpx:cad>")
            lines.append("          </gpxtpx:TrackPointExtension>")
            lines.append("        </extensions>")
        lines.append("      </trkpt>")
    lines.append("    </trkseg>")
    lines.append("  </trk>")
    lines.append("</gpx>")
    return "\n".join(lines) + "\n"


def _format_time(timestamp: float) -> str:
    dt = datetime.fromtimestamp(timestamp, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def _escape_xml(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )
