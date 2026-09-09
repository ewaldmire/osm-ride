from osm_ride_linux.weight.navy_body_fat import estimate_percent


def test_returns_a_reasonable_estimate_for_typical_inputs():
    # ~5'9" (175cm), 34in waist (86.4cm), 15in neck (38.1cm) - a lean-ish build.
    percent = estimate_percent(waist_cm=86.4, neck_cm=38.1, height_cm=175.0)
    assert percent is not None
    assert 5.0 < percent < 20.0


def test_returns_none_when_waist_not_greater_than_neck():
    assert estimate_percent(waist_cm=38.0, neck_cm=38.1, height_cm=175.0) is None
    assert estimate_percent(waist_cm=38.1, neck_cm=38.1, height_cm=175.0) is None


def test_returns_none_for_non_positive_height():
    assert estimate_percent(waist_cm=86.4, neck_cm=38.1, height_cm=0.0) is None
    assert estimate_percent(waist_cm=86.4, neck_cm=38.1, height_cm=-10.0) is None
