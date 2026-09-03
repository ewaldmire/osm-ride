from osm_ride_linux.route.gpx import parse

_NAMESPACED_GPX = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Test Loop</name>
    <trkseg>
      <trkpt lat="40.1106" lon="-88.2073"><ele>220.0</ele></trkpt>
      <trkpt lat="40.1206" lon="-88.2073"><ele>225.5</ele></trkpt>
      <trkpt lat="40.1306" lon="-88.2073"><ele>222.0</ele></trkpt>
    </trkseg>
  </trk>
</gpx>"""


def test_parses_namespaced_gpx():
    # Real-world GPX exports (RideWithGPS, Strava, komoot) declare a default XML namespace -
    # this must still parse tags by local name, matching the Kotlin parser's
    # isNamespaceAware = false behavior.
    parsed = parse(_NAMESPACED_GPX)
    assert parsed.name == "Test Loop"
    assert len(parsed.points) == 3


def test_elevation_gain_only_counts_climbs():
    parsed = parse(_NAMESPACED_GPX)
    # 220 -> 225.5 is a 5.5m climb; 225.5 -> 222 is a descent and shouldn't count.
    assert parsed.elevation_gain_meters == 5.5


def test_cumulative_distance_is_monotonic_and_matches_total():
    parsed = parse(_NAMESPACED_GPX)
    assert parsed.points[0].cumulative_distance_meters == 0.0
    distances = [p.cumulative_distance_meters for p in parsed.points]
    assert distances == sorted(distances)
    assert parsed.points[-1].cumulative_distance_meters == parsed.total_distance_meters


def test_rtept_route_points_also_parse():
    gpx_text = """<gpx version="1.1"><rte><name>A Route</name>
      <rtept lat="1.0" lon="1.0"/><rtept lat="1.01" lon="1.0"/>
    </rte></gpx>"""
    parsed = parse(gpx_text)
    assert parsed.name == "A Route"
    assert len(parsed.points) == 2


def test_no_usable_points_returns_empty():
    parsed = parse('<gpx version="1.1"></gpx>')
    assert parsed.points == []
    assert parsed.total_distance_meters == 0.0
