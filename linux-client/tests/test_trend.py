from osm_ride_linux.weight.trend import TrendPoint, rolling_average, weekly_delta

_DAY = 24 * 60 * 60 * 1000


def test_rolling_average_of_constant_values_equals_that_value():
    points = [TrendPoint(i * _DAY, 100.0) for i in range(5)]
    averaged = rolling_average(points, window_millis=7 * _DAY)
    assert [p.value for p in averaged] == [100.0] * 5


def test_rolling_average_only_includes_points_within_the_trailing_window():
    points = [
        TrendPoint(0, 100.0),
        TrendPoint(10 * _DAY, 200.0),  # 10 days later - outside a 7-day window from itself + point 0
    ]
    averaged = rolling_average(points, window_millis=7 * _DAY)
    assert averaged[0].value == 100.0
    assert averaged[1].value == 200.0  # point 0 fell out of the window, so avg is just itself


def test_rolling_average_widens_as_points_accumulate_within_window():
    points = [TrendPoint(0, 100.0), TrendPoint(1 * _DAY, 200.0), TrendPoint(2 * _DAY, 300.0)]
    averaged = rolling_average(points, window_millis=7 * _DAY)
    assert averaged[0].value == 100.0
    assert averaged[1].value == 150.0
    assert averaged[2].value == 200.0


def test_weekly_delta_none_when_no_recent_points():
    assert weekly_delta([], now_millis=100 * _DAY) is None


def test_weekly_delta_has_no_previous_average_without_older_history():
    points = [TrendPoint(99 * _DAY, 180.0), TrendPoint(100 * _DAY, 178.0)]
    delta = weekly_delta(points, now_millis=100 * _DAY)
    assert delta is not None
    assert delta.current_avg == 179.0
    assert delta.previous_avg is None
    assert delta.delta is None


def test_weekly_delta_compares_current_week_to_previous_week():
    points = [
        TrendPoint(87 * _DAY, 182.0),  # previous week: [now-14d, now-7d) = [86d, 93d)
        TrendPoint(88 * _DAY, 180.0),  # previous week
        TrendPoint(99 * _DAY, 178.0),  # current week: >= now-7d = 93d
        TrendPoint(100 * _DAY, 176.0),  # current week
    ]
    delta = weekly_delta(points, now_millis=100 * _DAY)
    assert delta is not None
    assert delta.current_avg == 177.0
    assert delta.previous_avg == 181.0
    assert delta.delta == -4.0
