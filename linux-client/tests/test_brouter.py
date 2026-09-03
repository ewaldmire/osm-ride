"""Integration test against the real BRouter public API - skips (rather than fails) on network
trouble, since that's an external service being unreachable, not a bug in this code."""

import pytest

from osm_ride_linux.route.brouter_client import BRouterError, route
from osm_ride_linux.route.gpx import parse
from osm_ride_linux.route.models import RouteWaypoint


def test_routes_waypoints_onto_real_roads():
    waypoints = [RouteWaypoint(40.1106, -88.2073), RouteWaypoint(40.1206, -88.1973)]
    try:
        gpx_text = route(waypoints)
    except BRouterError as e:
        pytest.skip(f"BRouter unreachable: {e}")

    parsed = parse(gpx_text)
    # A routed path between two points ~1.3km apart following real roads should produce many
    # more points than the 2 waypoints that went in.
    assert len(parsed.points) > 2
    assert parsed.total_distance_meters > 0


def test_raises_with_fewer_than_two_waypoints():
    with pytest.raises(BRouterError):
        route([RouteWaypoint(1.0, 1.0)])
