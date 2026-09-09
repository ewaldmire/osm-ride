"""Parses .fit files (Garmin's binary format, as used by bike computers, GPS watches, and
exports from Strava/RideWithGPS/etc. for outdoor rides) via the `fitparse` library - unlike
gpx_writer/erg_parser/zwo_parser, which hand-parse their (text) formats, FIT is binary and a
mature pure-Python decoder already exists, so there's no reason to reimplement one (see
FitFileParser.kt on the Android side, which - lacking an equivalent off-the-shelf JVM
dependency - does hand-roll a parser, decoding the same fields this reads).

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/FitFileParser.kt's output shape
(FitRideSummary/FitRidePoint), though not its implementation.
"""

from __future__ import annotations

import calendar
from dataclasses import dataclass

from fitparse import FitFile


@dataclass(frozen=True)
class FitRidePoint:
    timestamp: float  # epoch seconds, matching RecordedTrackPoint's unit convention
    lat: float | None
    lon: float | None
    elevation_meters: float | None
    heart_rate_bpm: int | None
    cadence_rpm: float | None
    power_watts: int | None
    cumulative_distance_meters: float | None


@dataclass(frozen=True)
class FitRideSummary:
    start_epoch_seconds: float
    # The ride's own last recorded timestamp - used as RideRecord.completed_at_epoch_millis,
    # consistent with how a live ride is saved right as it finishes.
    end_epoch_seconds: float
    duration_seconds: float
    distance_meters: float
    avg_speed_mps: float
    avg_power_watts: float | None
    avg_cadence_rpm: float | None
    avg_heart_rate_bpm: float | None
    total_calories: float | None
    points: list[FitRidePoint]


_SEMICIRCLE_TO_DEGREES = 180.0 / 2**31


def _to_epoch_seconds(dt) -> float:  # noqa: ANN001 - datetime.datetime
    """fitparse returns naive datetimes whose fields are UTC (FIT timestamps are computed from a
    fixed UTC epoch) but carry no tzinfo - datetime.timestamp() on a naive value assumes the
    *local* timezone instead, which would silently shift every imported ride by the system's UTC
    offset. calendar.timegm() treats the fields as UTC directly, which is what we actually want."""
    return calendar.timegm(dt.timetuple()) + dt.microsecond / 1_000_000


def parse(path: str) -> FitRideSummary | None:
    """None on anything unparseable (wrong file type, no usable records) - a bad import should
    silently no-op in the UI, not crash it."""
    try:
        return _parse(path)
    except Exception:
        return None


def _parse(path: str) -> FitRideSummary | None:
    fit_file = FitFile(path)

    points: list[FitRidePoint] = []
    for message in fit_file.get_messages("record"):
        values = {f.name: f.value for f in message}
        timestamp = values.get("timestamp")
        if timestamp is None:
            continue
        lat_semi = values.get("position_lat")
        lon_semi = values.get("position_long")
        points.append(
            FitRidePoint(
                timestamp=_to_epoch_seconds(timestamp),
                lat=lat_semi * _SEMICIRCLE_TO_DEGREES if lat_semi is not None else None,
                lon=lon_semi * _SEMICIRCLE_TO_DEGREES if lon_semi is not None else None,
                elevation_meters=values.get("altitude"),
                heart_rate_bpm=values.get("heart_rate"),
                cadence_rpm=float(values["cadence"]) if values.get("cadence") is not None else None,
                power_watts=values.get("power"),
                cumulative_distance_meters=values.get("distance"),
            )
        )
    if not points:
        return None

    # Multi-lap/multi-sport files aren't specially handled: every record is read regardless of
    # lap/session boundaries, and only the first session message's summary fields are used
    # (falling back to computing them from records if absent or if there's no session message at
    # all) - single-session outdoor rides are the overwhelming common case for "import my ride."
    session_values: dict = {}
    for message in fit_file.get_messages("session"):
        session_values = {f.name: f.value for f in message}
        break

    start = points[0].timestamp
    end = points[-1].timestamp
    computed_duration = max(0.0, end - start)
    computed_distance = max(
        (p.cumulative_distance_meters for p in points if p.cumulative_distance_meters is not None),
        default=0.0,
    )

    duration = session_values.get("total_elapsed_time")
    if duration is None:
        duration = computed_duration
    distance = session_values.get("total_distance")
    if distance is None:
        distance = computed_distance
    avg_speed = session_values.get("avg_speed")
    if avg_speed is None:
        avg_speed = distance / duration if duration > 0 else 0.0
    avg_power = session_values.get("avg_power")
    if avg_power is None:
        powers = [p.power_watts for p in points if p.power_watts is not None]
        avg_power = (sum(powers) / len(powers)) if powers else None
    avg_cadence = session_values.get("avg_cadence")
    if avg_cadence is None:
        cadences = [p.cadence_rpm for p in points if p.cadence_rpm is not None]
        avg_cadence = (sum(cadences) / len(cadences)) if cadences else None
    avg_heart_rate = session_values.get("avg_heart_rate")
    if avg_heart_rate is None:
        heart_rates = [p.heart_rate_bpm for p in points if p.heart_rate_bpm is not None]
        avg_heart_rate = (sum(heart_rates) / len(heart_rates)) if heart_rates else None
    # total_calories, when present, comes from the recording device's own HR/profile-based
    # estimate - more accurate for outdoor riding than the mechanical-power-only estimate
    # RideStats.estimated_kilocalories uses for live indoor rides (which has no HR-based model to
    # fall back on), so prefer it outright rather than recomputing.
    total_calories = session_values.get("total_calories")
    if total_calories is None and avg_power is not None:
        total_calories = avg_power * duration / 1000.0

    return FitRideSummary(
        start_epoch_seconds=start,
        end_epoch_seconds=end,
        duration_seconds=duration,
        distance_meters=distance,
        avg_speed_mps=avg_speed,
        avg_power_watts=avg_power,
        avg_cadence_rpm=avg_cadence,
        avg_heart_rate_bpm=avg_heart_rate,
        total_calories=total_calories,
        points=points,
    )
