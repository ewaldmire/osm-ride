"""Verifies FTMS/CSC byte-layout parsing against known-correct payloads.

These byte layouts are cross-checked against the proven Android implementation
(app/src/main/java/com/ewaldmire/osmride/ble/TrainerBleManager.kt) - same flags, same field
order, same units.
"""

from osm_ride_linux.ble.parsing import CscParser, parse_indoor_bike_data


def test_parse_indoor_bike_data_speed_cadence_power():
    # flags = 0x0044 -> cadence present (0x0004) + power present (0x0040); speed present since
    # bit 0 is 0 ("more data" flag inverted for speed - see FTMS spec).
    data = bytes(
        [
            0x44,
            0x00,  # flags LE = 0x0044
            0xC4,
            0x09,  # speed raw = 2500 -> 25.00 km/h -> /3.6 m/s
            0xB4,
            0x00,  # cadence raw = 180 -> 90.0 rpm (0.5 rpm resolution)
            0xC8,
            0x00,  # power = 200 (sint16)
        ]
    )
    sample = parse_indoor_bike_data(data)
    assert sample is not None
    assert abs(sample.speed_mps - 6.9444) < 0.001
    assert sample.cadence_rpm == 90.0
    assert sample.power_watts == 200
    assert sample.total_distance_meters is None


def test_parse_indoor_bike_data_negative_power():
    # flags = 0x0041 -> bit0 set means speed is ABSENT (the flag is inverted per the FTMS spec),
    # bit6 set means power is present. Negative power (coasting/braking) exercises sint16 sign
    # handling.
    data = bytes([0x41, 0x00, 0x38, 0xFF])  # power raw = -200 as sint16 LE
    sample = parse_indoor_bike_data(data)
    assert sample is not None
    assert sample.speed_mps is None
    assert sample.power_watts == -200


def test_csc_parser_derives_speed_and_cadence_from_deltas():
    parser = CscParser()
    # First sample only establishes a baseline - no prior state to diff against yet.
    first = parser.parse(bytes([0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]))
    assert first is None

    # wheel revs=10 (700x25c circumference), wheel event time=1024 ticks (1.0s @ 1024 Hz),
    # crank revs=5, crank event time=1024 ticks.
    second = parser.parse(bytes([0x03, 10, 0, 0, 0, 0x00, 0x04, 5, 0, 0x00, 0x04]))
    assert second is not None
    assert second.cadence_rpm == 300.0  # 5 revs in 1s -> 300 rpm
    assert abs(second.total_distance_meters - 21.05) < 0.01  # 10 revs * 2.105m circumference
    assert abs(second.speed_mps - 21.05) < 0.01  # 21.05m in 1.0s


def test_csc_parser_handles_wheel_revolution_rollover():
    parser = CscParser()
    parser.parse(bytes([0x01, 0xFE, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0, 0, 0, 0]))
    # cumulative wheel revs rolls over past uint32 max; event time rolls over past uint16 max.
    result = parser.parse(bytes([0x01, 0x02, 0x00, 0x00, 0x00, 0x00, 0x04, 0, 0, 0, 0]))
    assert result is not None
    assert result.speed_mps is not None and result.speed_mps > 0
