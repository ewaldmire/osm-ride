"""Fixtures here are hand-built raw FIT bytes (not recordings from a real device) - the field
layout was cross-checked against a real FIT decoder (fitparse itself) before this was written, so
these bytes are known-spec-valid, not just "whatever this repo's own parser happens to accept."
"""

from __future__ import annotations

import struct
from pathlib import Path

import pytest
from fitparse.records import Crc

from osm_ride_linux.ride import fit_parser

_SEMI = 2**31 / 180.0
_FIT_EPOCH_OFFSET = 631065600  # 1989-12-31T00:00:00Z, in Unix epoch seconds


def _u16(v: int) -> bytes:
    return struct.pack("<H", v)


def _u32(v: int) -> bytes:
    return struct.pack("<I", v)


def _s32(v: int) -> bytes:
    return struct.pack("<i", v)


def _to_semi(deg: float) -> int:
    return round(deg * _SEMI)


def _to_fit_ts(unix_ts: int) -> int:
    return unix_ts - _FIT_EPOCH_OFFSET


def _record_definition(local_type: int, fields: list[tuple[int, int, int]]) -> bytes:
    data = bytes([0x40 | local_type, 0, 0]) + _u16(20)  # global msg 20 = record
    data += bytes([len(fields)])
    for fn, sz, bt in fields:
        data += bytes([fn, sz, bt])
    return data


def _session_definition(local_type: int, fields: list[tuple[int, int, int]]) -> bytes:
    data = bytes([0x40 | local_type, 0, 0]) + _u16(18)  # global msg 18 = session
    data += bytes([len(fields)])
    for fn, sz, bt in fields:
        data += bytes([fn, sz, bt])
    return data


def _build_fit(body: bytes) -> bytes:
    header = bytes([12, 0x10]) + _u16(2078) + _u32(len(body)) + b".FIT"
    # fitparse validates the trailing file CRC by default (a real, worthwhile check against
    # corrupted files in production) - computed here via fitparse's own Crc class (the standard
    # FIT CRC-16, over the whole file including the header) rather than skipping validation, so
    # these fixtures are genuinely spec-valid, not just accepted because checking was disabled.
    crc = Crc.calculate(header + body)
    return header + body + struct.pack("<H", crc)


def _write(tmp_path: Path, name: str, fit_bytes: bytes) -> str:
    path = tmp_path / name
    path.write_bytes(fit_bytes)
    return str(path)


_RECORD_FIELDS = [
    (253, 4, 0x86),  # timestamp uint32
    (0, 4, 0x85),  # position_lat sint32
    (1, 4, 0x85),  # position_long sint32
    (5, 4, 0x86),  # distance uint32 (scale 100 -> m, applied by fitparse itself)
    (3, 1, 0x02),  # heart_rate uint8
    (4, 1, 0x02),  # cadence uint8
    (7, 2, 0x84),  # power uint16
]
_SESSION_FIELDS = [
    (253, 4, 0x86),  # timestamp
    (2, 4, 0x86),  # start_time
    (7, 4, 0x86),  # total_elapsed_time (scale 1000 -> s)
    (9, 4, 0x86),  # total_distance (scale 100 -> m)
    (14, 2, 0x84),  # avg_speed (scale 1000 -> m/s)
    (16, 1, 0x02),  # avg_heart_rate
    (18, 1, 0x02),  # avg_cadence
    (20, 2, 0x84),  # avg_power
    (11, 2, 0x84),  # total_calories
    (5, 1, 0x00),  # sport (enum) - 41 = kayaking
]


def _record_data(t0: int, offset: int, lat: float, lon: float, dist_cm: int, hr: int, cad: int, power: int) -> bytes:
    return (
        bytes([0x00])
        + _u32(_to_fit_ts(t0 + offset))
        + _s32(_to_semi(lat))
        + _s32(_to_semi(lon))
        + _u32(dist_cm)
        + bytes([hr, cad])
        + _u16(power)
    )


@pytest.fixture
def full_fit_file(tmp_path: Path) -> tuple[str, int]:
    t0 = 1_700_000_000
    body = _record_definition(0, _RECORD_FIELDS)
    body += _record_data(t0, 0, 37.7749, -122.4194, 0, 120, 80, 150)
    body += _record_data(t0, 10, 37.77495, -122.41945, 5000, 125, 82, 160)
    body += _record_data(t0, 20, 37.7750, -122.4195, 10000, 130, 85, 170)

    body += _session_definition(1, _SESSION_FIELDS)
    body += (
        bytes([0x01])
        + _u32(_to_fit_ts(t0 + 20))
        + _u32(_to_fit_ts(t0))
        + _u32(20000)
        + _u32(10000)
        + _u16(5000)
        + bytes([125, 82])
        + _u16(160)
        + _u16(50)
        + bytes([41])  # sport = kayaking - this is the exact real-world case that motivated
        # tracking activity type at all: an outdoor .fit import that isn't a bike ride.
    )
    return _write(tmp_path, "full.fit", _build_fit(body)), t0


def test_parses_points_and_session_summary(full_fit_file: tuple[str, int]):
    path, t0 = full_fit_file
    summary = fit_parser.parse(path)

    assert summary is not None
    assert summary.start_epoch_seconds == t0
    assert summary.end_epoch_seconds == t0 + 20
    assert summary.duration_seconds == 20.0
    assert summary.distance_meters == 100.0
    assert summary.avg_speed_mps == 5.0
    assert summary.avg_power_watts == 160
    assert summary.avg_cadence_rpm == 82
    assert summary.avg_heart_rate_bpm == 125
    assert summary.total_calories == 50
    assert summary.activity_type == "kayaking"

    assert len(summary.points) == 3
    first = summary.points[0]
    assert first.timestamp == t0
    assert first.lat == pytest.approx(37.7749, abs=1e-4)
    assert first.lon == pytest.approx(-122.4194, abs=1e-4)
    assert first.heart_rate_bpm == 120
    assert first.cadence_rpm == 80
    assert first.power_watts == 150
    assert first.cumulative_distance_meters == 0.0
    assert summary.points[-1].cumulative_distance_meters == 100.0


def test_falls_back_to_computed_summary_without_a_session_message(tmp_path: Path):
    t0 = 1_700_000_500
    body = _record_definition(0, [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85), (5, 4, 0x86)])
    body += bytes([0x00]) + _u32(_to_fit_ts(t0)) + _s32(_to_semi(40.0)) + _s32(_to_semi(-105.0)) + _u32(0)
    body += bytes([0x00]) + _u32(_to_fit_ts(t0 + 10)) + _s32(_to_semi(40.001)) + _s32(_to_semi(-105.001)) + _u32(3000)
    path = _write(tmp_path, "no_session.fit", _build_fit(body))

    summary = fit_parser.parse(path)

    assert summary is not None
    assert summary.duration_seconds == 10.0
    assert summary.distance_meters == 30.0
    assert summary.avg_speed_mps == pytest.approx(3.0)
    assert summary.avg_power_watts is None
    assert summary.total_calories is None
    assert summary.activity_type == "other"


def test_walks_past_an_unrelated_interleaved_message_type(tmp_path: Path):
    # A file_id message (global 0) before the record definition - every real FIT file has one;
    # this confirms messages this parser doesn't care about don't derail record/session parsing.
    body = bytes([0x40 | 2, 0, 0]) + _u16(0)  # definition, local type 2, global msg 0 = file_id
    file_id_fields = [(0, 1, 0x00), (1, 2, 0x84), (4, 4, 0x86)]
    body += bytes([len(file_id_fields)])
    for fn, sz, bt in file_id_fields:
        body += bytes([fn, sz, bt])
    body += bytes([0x02]) + bytes([4]) + _u16(1) + _u32(12345)

    t0 = 1_700_001_000
    body += _record_definition(0, [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85)])
    body += bytes([0x00]) + _u32(_to_fit_ts(t0)) + _s32(_to_semi(10.0)) + _s32(_to_semi(20.0))
    body += bytes([0x00]) + _u32(_to_fit_ts(t0 + 5)) + _s32(_to_semi(10.001)) + _s32(_to_semi(20.001))
    path = _write(tmp_path, "interleaved.fit", _build_fit(body))

    summary = fit_parser.parse(path)

    assert summary is not None
    assert len(summary.points) == 2
    assert summary.duration_seconds == 5.0


def test_returns_none_for_a_non_fit_file(tmp_path: Path):
    path = tmp_path / "not_a_fit_file.txt"
    path.write_text("this is not a FIT file")
    assert fit_parser.parse(str(path)) is None


def test_returns_none_for_a_fit_file_with_no_records(tmp_path: Path):
    # No data records at all - just header + CRC.
    path = _write(tmp_path, "empty.fit", _build_fit(b""))
    assert fit_parser.parse(path) is None
