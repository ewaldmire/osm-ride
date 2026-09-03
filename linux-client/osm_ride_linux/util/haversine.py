"""Great-circle distance/bearing calculations.

Mirrors app/src/main/java/com/ewaldmire/osmride/util/Haversine.kt exactly.
"""

from __future__ import annotations

import math

_EARTH_RADIUS_METERS = 6_371_000.0


def distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    d_lat = math.radians(lat2 - lat1)
    d_lon = math.radians(lon2 - lon1)
    a = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(
        d_lon / 2
    ) ** 2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return _EARTH_RADIUS_METERS * c


def bearing_degrees(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial compass bearing in degrees [0, 360) from point 1 to point 2."""
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_lon = math.radians(lon2 - lon1)
    y = math.sin(d_lon) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lon)
    theta = math.atan2(y, x)
    return (math.degrees(theta) + 360) % 360
