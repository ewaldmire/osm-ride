"""Derives a sparse, editable RouteWaypoint list from a dense recorded/imported track.

Used to make imported GPX routes editable in the Route Creator, which drags/re-routes a small
set of waypoints via BRouter rather than the full recorded polyline. Mirrors
app/src/main/java/com/ewaldmire/osmride/route/WaypointSimplifier.kt.
"""

from __future__ import annotations

from ..util.haversine import bearing_degrees
from .models import RoutePoint, RouteWaypoint

# Roughly one waypoint every 400m along straight stretches - a round metric distance, not a
# mile-derived one, since cumulative_distance_meters is already metric internally regardless of
# the app's imperial display units.
_MIN_SPACING_METERS = 400.0
# A bearing change at or above this, between the incoming and outgoing segment, forces a waypoint
# even if it's short of _MIN_SPACING_METERS - otherwise a sharp turn that happens to fall inside
# one sampling interval would get smoothed away when BRouter re-routes between its neighbors.
_TURN_THRESHOLD_DEGREES = 30.0
# Floor below which a "sharp turn" still isn't kept - without this, GPS jitter/noise near a real
# turn could produce a cluster of near-duplicate forced waypoints.
_MIN_TURN_SPACING_METERS = 50.0
# Widens the effective spacing for very long routes so a 100+ mile import doesn't produce an
# unwieldy number of waypoints (a long BRouter request, a map full of indistinguishable pins).
_MAX_WAYPOINTS = 150


def derive_waypoints(points: list[RoutePoint]) -> list[RouteWaypoint]:
    if len(points) <= 2:
        return [RouteWaypoint(lat=p.lat, lon=p.lon) for p in points]

    total_distance = points[-1].cumulative_distance_meters
    min_spacing = max(_MIN_SPACING_METERS, total_distance / _MAX_WAYPOINTS)

    kept = [points[0]]
    last_kept_distance = points[0].cumulative_distance_meters

    for i in range(1, len(points) - 1):
        point = points[i]
        distance_since_last = point.cumulative_distance_meters - last_kept_distance
        if distance_since_last >= min_spacing:
            kept.append(point)
            last_kept_distance = point.cumulative_distance_meters
            continue
        if distance_since_last >= _MIN_TURN_SPACING_METERS and _is_sharp_turn(
            points[i - 1], point, points[i + 1]
        ):
            kept.append(point)
            last_kept_distance = point.cumulative_distance_meters

    kept.append(points[-1])
    return [RouteWaypoint(lat=p.lat, lon=p.lon) for p in kept]


def _is_sharp_turn(before: RoutePoint, at: RoutePoint, after: RoutePoint) -> bool:
    incoming_bearing = bearing_degrees(before.lat, before.lon, at.lat, at.lon)
    outgoing_bearing = bearing_degrees(at.lat, at.lon, after.lat, after.lon)
    turn_angle = abs(outgoing_bearing - incoming_bearing) % 360
    if turn_angle > 180:
        turn_angle = 360 - turn_angle
    return turn_angle >= _TURN_THRESHOLD_DEGREES
