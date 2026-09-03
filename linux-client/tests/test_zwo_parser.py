from osm_ride_linux.ride import zwo_parser

_ZWO_TEXT = """<?xml version="1.0" encoding="UTF-8"?>
<workout_file>
  <name>Test Zwo</name>
  <workout>
    <Warmup Duration="300" PowerLow="0.5" PowerHigh="0.75"/>
    <SteadyState Duration="120" Power="0.8"/>
    <IntervalsT Repeat="3" OnDuration="30" OffDuration="15" OnPower="1.2" OffPower="0.5"/>
    <FreeRide Duration="60"/>
    <MaxEffort Duration="20"/>
    <Cooldown Duration="180" PowerLow="0.6" PowerHigh="0.4"/>
  </workout>
</workout_file>
"""


def test_parses_name_and_segment_count():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=200, fallback_name="fallback")
    assert parsed.name == "Test Zwo"
    # Warmup(1) + SteadyState(1) + IntervalsT(3 repeats * 2 = 6) + FreeRide(1) + MaxEffort(1) + Cooldown(1)
    assert len(parsed.segments) == 11


def test_warmup_ramp_uses_low_high_as_start_end():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=200, fallback_name="fallback")
    warmup = parsed.segments[0]
    assert warmup.start_seconds == 0
    assert warmup.end_seconds == 300
    assert warmup.start_watts == 100  # 0.5 * 200
    assert warmup.end_watts == 150  # 0.75 * 200


def test_steady_state_uses_flat_power_for_both_ends():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=200, fallback_name="fallback")
    steady = parsed.segments[1]
    assert steady.start_watts == 160  # 0.8 * 200
    assert steady.end_watts == 160


def test_intervals_expand_to_repeated_on_off_pairs():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=200, fallback_name="fallback")
    interval_segments = parsed.segments[2:8]  # 3 repeats * 2 segments each
    assert len(interval_segments) == 6
    on_segments = interval_segments[0::2]
    off_segments = interval_segments[1::2]
    assert all(s.start_watts == 240 for s in on_segments)  # 1.2 * 200
    assert all(s.start_watts == 100 for s in off_segments)  # 0.5 * 200
    assert all((s.end_seconds - s.start_seconds) == 30 for s in on_segments)
    assert all((s.end_seconds - s.start_seconds) == 15 for s in off_segments)


def test_free_ride_and_max_effort_have_no_target_watts_when_unspecified():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=200, fallback_name="fallback")
    free_ride = parsed.segments[8]
    max_effort = parsed.segments[9]
    assert free_ride.start_watts is None
    assert max_effort.start_watts is None
    assert max_effort.end_watts is None


def test_total_duration_matches_sum_of_segments():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=200, fallback_name="fallback")
    expected = 300 + 120 + (30 + 15) * 3 + 60 + 20 + 180
    assert parsed.total_duration_seconds == expected


def test_missing_ftp_yields_none_watts_throughout():
    parsed = zwo_parser.parse(_ZWO_TEXT, ftp_watts=None, fallback_name="fallback")
    assert all(s.start_watts is None and s.end_watts is None for s in parsed.segments)


def test_falls_back_to_fallback_name_when_no_name_element():
    text = '<workout_file><workout><SteadyState Duration="60" Power="1.0"/></workout></workout_file>'
    parsed = zwo_parser.parse(text, ftp_watts=200, fallback_name="fallback_used")
    assert parsed.name == "fallback_used"
