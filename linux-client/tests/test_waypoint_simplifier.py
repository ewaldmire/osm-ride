from osm_ride_linux.route.models import RoutePoint
from osm_ride_linux.route.waypoint_simplifier import derive_waypoints
from osm_ride_linux.util.haversine import distance_meters

_LAT_STEP_FOR_50M = 0.00045  # roughly 50m of latitude at typical mid-latitudes
_START_LAT = 40.0
_START_LON = -88.0


def _points_along_meridian(count: int) -> list[RoutePoint]:
    """A dense straight-line track heading due north, one point every ~50m."""
    points = []
    cumulative = 0.0
    prev = None
    for i in range(count):
        lat = _START_LAT + i * _LAT_STEP_FOR_50M
        lon = _START_LON
        if prev is not None:
            cumulative += distance_meters(prev[0], prev[1], lat, lon)
        points.append(RoutePoint(lat=lat, lon=lon, elevation_meters=None, cumulative_distance_meters=cumulative))
        prev = (lat, lon)
    return points


def test_straight_line_gets_widely_spaced_waypoints():
    # ~41 points spaced 50m apart along a straight line (~2000m total) - the corner-preservation
    # rule never fires on a straight line, so this exercises pure distance-based sampling.
    points = _points_along_meridian(41)
    waypoints = derive_waypoints(points)

    # Far fewer waypoints than input points, but still enough to represent a 2km route at
    # ~400m spacing (roughly 5-7 waypoints including start/end).
    assert 4 <= len(waypoints) <= 8
    assert (waypoints[0].lat, waypoints[0].lon) == (points[0].lat, points[0].lon)
    assert (waypoints[-1].lat, waypoints[-1].lon) == (points[-1].lat, points[-1].lon)


def test_sharp_turn_is_preserved_even_when_close_to_start():
    # Leg 1: heading due north for ~150m (3 points, 50m apart). Leg 2: sharp 90-degree turn
    # heading due east for ~150m. The corner point is only ~150m from the route start - well
    # under the 400m spacing threshold - so without turn-preservation it would be dropped.
    points = []
    cumulative = 0.0
    prev = None
    for i in range(4):
        lat = _START_LAT + i * _LAT_STEP_FOR_50M
        lon = _START_LON
        if prev is not None:
            cumulative += distance_meters(prev[0], prev[1], lat, lon)
        points.append(RoutePoint(lat=lat, lon=lon, elevation_meters=None, cumulative_distance_meters=cumulative))
        prev = (lat, lon)

    corner_lat = points[-1].lat
    corner_lon = points[-1].lon
    for i in range(1, 4):
        lon = corner_lon + i * _LAT_STEP_FOR_50M
        lat = corner_lat
        cumulative += distance_meters(prev[0], prev[1], lat, lon)
        points.append(RoutePoint(lat=lat, lon=lon, elevation_meters=None, cumulative_distance_meters=cumulative))
        prev = (lat, lon)

    waypoints = derive_waypoints(points)
    corner = (corner_lat, corner_lon)
    assert any((wp.lat, wp.lon) == corner for wp in waypoints)


def test_very_long_route_stays_under_waypoint_cap():
    # ~4000 points spaced 50m apart (~200km total) - without the dynamic spacing cap this would
    # naively produce ~500 waypoints at the 400m default spacing.
    points = _points_along_meridian(4000)
    waypoints = derive_waypoints(points)
    assert len(waypoints) <= 152  # MAX_WAYPOINTS (150) plus a small margin for start/end


def test_short_route_keeps_every_point():
    points = _points_along_meridian(2)
    waypoints = derive_waypoints(points)
    assert len(waypoints) == 2
