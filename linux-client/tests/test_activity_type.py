from osm_ride_linux.ride import activity_type


def test_maps_known_fit_sport_names():
    assert activity_type.from_fit_sport("cycling") == activity_type.CYCLING
    assert activity_type.from_fit_sport("e_biking") == activity_type.CYCLING
    assert activity_type.from_fit_sport("running") == activity_type.RUNNING
    assert activity_type.from_fit_sport("walking") == activity_type.WALKING
    assert activity_type.from_fit_sport("hiking") == activity_type.HIKING
    assert activity_type.from_fit_sport("swimming") == activity_type.SWIMMING
    assert activity_type.from_fit_sport("rowing") == activity_type.ROWING
    assert activity_type.from_fit_sport("kayaking") == activity_type.KAYAKING
    assert activity_type.from_fit_sport("paddling") == activity_type.KAYAKING
    assert activity_type.from_fit_sport("stand_up_paddleboarding") == activity_type.KAYAKING


def test_falls_back_to_other_for_unmapped_or_missing_sport():
    assert activity_type.from_fit_sport("golf") == activity_type.OTHER
    assert activity_type.from_fit_sport(None) == activity_type.OTHER
