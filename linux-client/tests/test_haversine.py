from osm_ride_linux.util.haversine import bearing_degrees, distance_meters


def test_one_degree_of_latitude_is_about_111km():
    d = distance_meters(0.0, 0.0, 1.0, 0.0)
    assert abs(d / 1000 - 111.19) < 0.5


def test_zero_distance_for_identical_points():
    assert distance_meters(40.0, -88.0, 40.0, -88.0) == 0.0


def test_bearing_due_north_is_zero():
    b = bearing_degrees(0.0, 0.0, 1.0, 0.0)
    assert abs(b - 0.0) < 0.01


def test_bearing_due_east_is_ninety():
    b = bearing_degrees(0.0, 0.0, 0.0, 1.0)
    assert abs(b - 90.0) < 0.01
