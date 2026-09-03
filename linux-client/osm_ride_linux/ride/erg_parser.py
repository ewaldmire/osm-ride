"""Parses the plain-text .erg/.mrc interval format used by GoldenCheetah, TrainerRoad, PerfPro
etc: a [COURSE HEADER]/[END COURSE HEADER] metadata block, then [COURSE DATA]/[END COURSE DATA]
containing tab/space-separated <minutes> <value> points defining a piecewise-linear power curve
(flat holds are just two consecutive points at the same value). .erg point values are absolute
watts; .mrc values are a percentage of FTP.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/ErgWorkoutParser.kt.
"""

from __future__ import annotations

import re

from .models import ParsedWorkout, WorkoutSegment

_WHITESPACE = re.compile(r"\s+")


def parse(text: str, is_percent_based: bool, ftp_watts: int | None, fallback_name: str) -> ParsedWorkout:
    name = fallback_name
    raw_points: list[tuple[float, float]] = []  # minutes, value (watts or percent)
    in_data = False

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(";"):
            continue

        if line.upper() == "[COURSE DATA]":
            in_data = True
            continue
        if line.upper() == "[END COURSE DATA]":
            in_data = False
            continue

        if not in_data:
            eq = line.find("=")
            if eq > 0:
                key = line[:eq].strip().upper()
                value = line[eq + 1 :].strip()
                if key in ("DESCRIPTION", "FILE NAME") and value.strip():
                    name = value
            continue

        parts = _WHITESPACE.split(line)
        if len(parts) < 2:
            continue
        try:
            minutes = float(parts[0])
            value = float(parts[1].removesuffix("%"))
        except ValueError:
            continue
        raw_points.append((minutes, value))

    if len(raw_points) < 2:
        return ParsedWorkout(name=name, segments=[], total_duration_seconds=0)

    segments: list[WorkoutSegment] = []
    for (m1, v1), (m2, v2) in zip(raw_points, raw_points[1:]):
        segments.append(
            WorkoutSegment(
                start_seconds=round(m1 * 60),
                end_seconds=round(m2 * 60),
                start_watts=_to_watts(v1, is_percent_based, ftp_watts),
                end_watts=_to_watts(v2, is_percent_based, ftp_watts),
            )
        )
    return ParsedWorkout(name=name, segments=segments, total_duration_seconds=segments[-1].end_seconds)


def _to_watts(value: float, is_percent_based: bool, ftp_watts: int | None) -> int | None:
    if not is_percent_based:
        return round(value)
    if ftp_watts is None:
        return None
    return round(value / 100.0 * ftp_watts)
