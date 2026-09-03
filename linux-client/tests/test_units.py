from osm_ride_linux.util import units


def test_format_miles():
    assert units.format_miles(1609.344) == "1.00 mi"


def test_format_duration_under_an_hour():
    assert units.format_duration(125) == "2:05"


def test_format_duration_over_an_hour():
    assert units.format_duration(3725) == "1:02:05"


def test_format_watts_none_is_dashes():
    assert units.format_watts(None) == "--"


def test_format_grade_shows_sign():
    assert units.format_grade(3.2) == "+3.2%"
    assert units.format_grade(-1.5) == "-1.5%"
