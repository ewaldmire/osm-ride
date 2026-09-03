"""FTMS Indoor Bike Data / CSC Measurement byte-layout parsing.

Mirrors app/src/main/java/com/ewaldmire/osmride/ble/BleParsing.kt and the parseIndoorBikeData/
parseCscMeasurement methods in TrainerBleManager.kt, field-for-field and bit-for-bit.
"""

from __future__ import annotations

from .constants import DEFAULT_WHEEL_CIRCUMFERENCE_METERS
from .models import TrainerSample


def _u16le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def _s16le(data: bytes, offset: int) -> int:
    value = _u16le(data, offset)
    return value - 0x10000 if value >= 0x8000 else value


def _u24le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8) | (data[offset + 2] << 16)


def _u32le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8) | (data[offset + 2] << 16) | (data[offset + 3] << 24)


def parse_indoor_bike_data(data: bytes) -> TrainerSample | None:
    if len(data) < 2:
        return None
    idx = 0
    flags = _u16le(data, idx)
    idx += 2

    speed = None
    if flags & 0x0001 == 0 and idx + 2 <= len(data):
        speed = _u16le(data, idx) * 0.01 / 3.6  # 0.01 km/h -> m/s
        idx += 2
    if flags & 0x0002 != 0:
        idx += 2  # average speed, unused
    cadence = None
    if flags & 0x0004 != 0 and idx + 2 <= len(data):
        cadence = _u16le(data, idx) * 0.5  # 0.5 rpm resolution
        idx += 2
    if flags & 0x0008 != 0:
        idx += 2  # average cadence, unused
    total_distance = None
    if flags & 0x0010 != 0 and idx + 3 <= len(data):
        total_distance = float(_u24le(data, idx))
        idx += 3
    if flags & 0x0020 != 0:
        idx += 2  # resistance level, unused
    power = None
    if flags & 0x0040 != 0 and idx + 2 <= len(data):
        power = _s16le(data, idx)
        idx += 2

    return TrainerSample(
        speed_mps=speed,
        cadence_rpm=cadence,
        power_watts=power,
        total_distance_meters=total_distance,
    )


class CscParser:
    """Stateful CSC Measurement parser - wheel/crank revolution counters roll over, so distance
    and cadence are derived from the delta against the previous sample, tracked per-connection."""

    def __init__(self) -> None:
        self._previous_wheel_revs: int | None = None
        self._previous_wheel_event_time: int | None = None
        self._previous_crank_revs: int | None = None
        self._previous_crank_event_time: int | None = None
        self._cumulative_distance_meters = 0.0

    def parse(self, data: bytes) -> TrainerSample | None:
        if len(data) == 0:
            return None
        flags = data[0]
        idx = 1
        speed = None
        total_distance = None

        if flags & 0x01 != 0 and idx + 6 <= len(data):
            cumulative_wheel_revs = _u32le(data, idx)
            last_wheel_event_time = _u16le(data, idx + 4)
            idx += 6

            prev_revs = self._previous_wheel_revs
            prev_time = self._previous_wheel_event_time
            if prev_revs is not None and prev_time is not None:
                rev_delta = cumulative_wheel_revs - prev_revs
                if rev_delta < 0:
                    rev_delta += 0x100000000
                time_delta_ticks = last_wheel_event_time - prev_time
                if time_delta_ticks < 0:
                    time_delta_ticks += 0x10000
                if time_delta_ticks > 0:
                    time_delta_seconds = time_delta_ticks / 1024.0
                    distance_delta = rev_delta * DEFAULT_WHEEL_CIRCUMFERENCE_METERS
                    speed = distance_delta / time_delta_seconds
                    self._cumulative_distance_meters += distance_delta
                    total_distance = self._cumulative_distance_meters
            self._previous_wheel_revs = cumulative_wheel_revs
            self._previous_wheel_event_time = last_wheel_event_time

        cadence = None
        if flags & 0x02 != 0 and idx + 4 <= len(data):
            cumulative_crank_revs = _u16le(data, idx)
            last_crank_event_time = _u16le(data, idx + 2)
            idx += 4

            prev_revs = self._previous_crank_revs
            prev_time = self._previous_crank_event_time
            if prev_revs is not None and prev_time is not None:
                rev_delta = cumulative_crank_revs - prev_revs
                if rev_delta < 0:
                    rev_delta += 0x10000
                time_delta_ticks = last_crank_event_time - prev_time
                if time_delta_ticks < 0:
                    time_delta_ticks += 0x10000
                if time_delta_ticks > 0:
                    time_delta_seconds = time_delta_ticks / 1024.0
                    cadence = rev_delta * 60.0 / time_delta_seconds
            self._previous_crank_revs = cumulative_crank_revs
            self._previous_crank_event_time = last_crank_event_time

        if speed is None and cadence is None:
            return None
        return TrainerSample(
            speed_mps=speed,
            cadence_rpm=cadence,
            power_watts=None,
            total_distance_meters=total_distance,
        )
