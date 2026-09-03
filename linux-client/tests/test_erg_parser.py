from osm_ride_linux.ride import erg_parser

_ERG_TEXT = """[COURSE HEADER]
VERSION = 2
UNITS = ENGLISH
DESCRIPTION = Test Workout
[END COURSE HEADER]
[COURSE DATA]
0.00\t100
5.00\t100
5.00\t250
10.00\t250
[END COURSE DATA]
"""

_MRC_TEXT = """[COURSE HEADER]
DESCRIPTION = Percent Workout
[END COURSE HEADER]
[COURSE DATA]
0.00\t50
10.00\t100
[END COURSE DATA]
"""


def test_parses_erg_absolute_watts():
    # 4 data points (0,100)(5,100)(5,250)(10,250) produce 3 pairwise segments - including a
    # zero-duration segment at the repeated 5-minute mark, which is the standard .erg convention
    # for an instantaneous step change (as opposed to a gradual ramp).
    parsed = erg_parser.parse(_ERG_TEXT, is_percent_based=False, ftp_watts=None, fallback_name="fallback")
    assert parsed.name == "Test Workout"
    assert len(parsed.segments) == 3
    # flat hold at 100W for 5 minutes
    assert parsed.segments[0].start_seconds == 0
    assert parsed.segments[0].end_seconds == 300
    assert parsed.segments[0].start_watts == 100
    assert parsed.segments[0].end_watts == 100
    # instant (zero-duration) step from 100W to 250W at the 5-minute mark
    assert parsed.segments[1].start_seconds == 300
    assert parsed.segments[1].end_seconds == 300
    assert parsed.segments[1].start_watts == 100
    assert parsed.segments[1].end_watts == 250
    # flat hold at 250W for the remaining 5 minutes
    assert parsed.segments[2].start_watts == 250
    assert parsed.segments[2].end_watts == 250
    assert parsed.total_duration_seconds == 600


def test_parses_mrc_percent_based_with_ftp():
    parsed = erg_parser.parse(_MRC_TEXT, is_percent_based=True, ftp_watts=200, fallback_name="fallback")
    assert parsed.name == "Percent Workout"
    assert len(parsed.segments) == 1
    assert parsed.segments[0].start_watts == 100  # 50% of 200W
    assert parsed.segments[0].end_watts == 200  # 100% of 200W


def test_percent_based_without_ftp_yields_none_watts():
    parsed = erg_parser.parse(_MRC_TEXT, is_percent_based=True, ftp_watts=None, fallback_name="fallback")
    assert parsed.segments[0].start_watts is None


def test_uses_fallback_name_when_no_description():
    text = "[COURSE DATA]\n0.00 100\n5.00 100\n[END COURSE DATA]\n"
    parsed = erg_parser.parse(text, is_percent_based=False, ftp_watts=None, fallback_name="my_workout")
    assert parsed.name == "my_workout"


def test_fewer_than_two_points_yields_no_segments():
    text = "[COURSE DATA]\n0.00 100\n[END COURSE DATA]\n"
    parsed = erg_parser.parse(text, is_percent_based=False, ftp_watts=None, fallback_name="fallback")
    assert parsed.segments == []
    assert parsed.total_duration_seconds == 0
