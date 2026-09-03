"""Parses a GPX 1.1 file's track (trkpt) or route (rtept) points.

Mirrors app/src/main/java/com/ewaldmire/osmride/route/GpxParser.kt field-for-field. GPX files
commonly declare a default XML namespace (xmlns="http://www.topografix.com/GPX/1/1"); the Kotlin
parser explicitly disables namespace-awareness (isNamespaceAware = false) so it matches tags by
local name regardless of namespace, which _local_name() below replicates.
"""

from __future__ import annotations

import io
import xml.etree.ElementTree as ET
from dataclasses import dataclass

from ..util.haversine import distance_meters
from .models import RoutePoint


@dataclass(frozen=True)
class ParsedGpx:
    name: str | None
    points: list[RoutePoint]
    total_distance_meters: float
    elevation_gain_meters: float


@dataclass
class _RawPoint:
    lat: float
    lon: float
    elevation_meters: float | None


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse(gpx_text: str) -> ParsedGpx:
    route_name: str | None = None
    raw_points: list[_RawPoint] = []

    in_point = False
    lat = 0.0
    lon = 0.0
    ele: float | None = None

    for event, elem in ET.iterparse(io.StringIO(gpx_text), events=("start", "end")):
        tag = _local_name(elem.tag)
        if event == "start":
            if tag in ("trkpt", "rtept"):
                in_point = True
                try:
                    lat = float(elem.get("lat", "0"))
                except ValueError:
                    lat = 0.0
                try:
                    lon = float(elem.get("lon", "0"))
                except ValueError:
                    lon = 0.0
                ele = None
        else:  # end
            text = (elem.text or "").strip()
            if tag == "ele" and in_point:
                try:
                    ele = float(text)
                except ValueError:
                    ele = None
            elif tag == "name":
                if route_name is None and text:
                    route_name = text
            elif tag in ("trkpt", "rtept"):
                raw_points.append(_RawPoint(lat, lon, ele))
                in_point = False

    return _build_parsed_gpx(route_name, raw_points)


def _build_parsed_gpx(name: str | None, raw_points: list[_RawPoint]) -> ParsedGpx:
    points: list[RoutePoint] = []
    cumulative = 0.0
    elevation_gain = 0.0
    previous: _RawPoint | None = None

    for raw in raw_points:
        if previous is not None:
            cumulative += distance_meters(previous.lat, previous.lon, raw.lat, raw.lon)
            prev_ele = previous.elevation_meters
            cur_ele = raw.elevation_meters
            if prev_ele is not None and cur_ele is not None and cur_ele > prev_ele:
                elevation_gain += cur_ele - prev_ele
        points.append(RoutePoint(raw.lat, raw.lon, raw.elevation_meters, cumulative))
        previous = raw

    return ParsedGpx(
        name=name,
        points=points,
        total_distance_meters=cumulative,
        elevation_gain_meters=elevation_gain,
    )
