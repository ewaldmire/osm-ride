"""Parses Zwift .zwo structured workout XML. Power attributes (Power/PowerLow/PowerHigh/
OnPower/OffPower) are fractions of FTP (e.g. "0.75" = 75%); Duration attributes are seconds.
MaxEffort and power-less FreeRide blocks become segments with None watts - no ERG target is sent
to the trainer for those stretches.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/ZwoWorkoutParser.kt.
"""

from __future__ import annotations

import io
import xml.etree.ElementTree as ET

from .models import ParsedWorkout, WorkoutSegment


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _attr_float(elem: ET.Element, attr: str) -> float | None:
    value = elem.get(attr)
    if value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def _to_watts(ftp_fraction: float, ftp_watts: int | None) -> int | None:
    if ftp_watts is None:
        return None
    return round(ftp_fraction * ftp_watts)


def parse(xml_text: str, ftp_watts: int | None, fallback_name: str) -> ParsedWorkout:
    name: str | None = None
    segments: list[WorkoutSegment] = []
    cursor_seconds = 0.0

    for _event, elem in ET.iterparse(io.StringIO(xml_text), events=("end",)):
        tag = _local_name(elem.tag)

        if tag == "name":
            text = (elem.text or "").strip()
            if text:
                name = text

        elif tag in ("Warmup", "Cooldown", "SteadyState", "Ramp"):
            cursor_seconds = _add_ramp_or_steady(elem, segments, cursor_seconds, ftp_watts)

        elif tag == "FreeRide":
            duration = _attr_float(elem, "Duration") or 0.0
            power_fraction = _attr_float(elem, "Power")
            watts = _to_watts(power_fraction, ftp_watts) if power_fraction is not None else None
            end = cursor_seconds + round(duration)
            segments.append(WorkoutSegment(cursor_seconds, end, watts, watts))
            cursor_seconds = end

        elif tag == "MaxEffort":
            duration = _attr_float(elem, "Duration") or 0.0
            end = cursor_seconds + round(duration)
            segments.append(WorkoutSegment(cursor_seconds, end, None, None))
            cursor_seconds = end

        elif tag == "IntervalsT":
            repeat_count = round(_attr_float(elem, "Repeat") or 1)
            on_duration = _attr_float(elem, "OnDuration") or 0.0
            off_duration = _attr_float(elem, "OffDuration") or 0.0
            on_power = _attr_float(elem, "OnPower")
            off_power = _attr_float(elem, "OffPower")
            on_watts = _to_watts(on_power, ftp_watts) if on_power is not None else None
            off_watts = _to_watts(off_power, ftp_watts) if off_power is not None else None
            for _ in range(repeat_count):
                on_end = cursor_seconds + round(on_duration)
                segments.append(WorkoutSegment(cursor_seconds, on_end, on_watts, on_watts))
                cursor_seconds = on_end
                off_end = cursor_seconds + round(off_duration)
                segments.append(WorkoutSegment(cursor_seconds, off_end, off_watts, off_watts))
                cursor_seconds = off_end

    return ParsedWorkout(
        name=(name.strip() if name and name.strip() else fallback_name),
        segments=segments,
        total_duration_seconds=cursor_seconds,
    )


def _add_ramp_or_steady(
    elem: ET.Element,
    segments: list[WorkoutSegment],
    cursor_seconds: float,
    ftp_watts: int | None,
) -> float:
    duration = _attr_float(elem, "Duration") or 0.0
    low = _attr_float(elem, "PowerLow")
    high = _attr_float(elem, "PowerHigh")
    flat = _attr_float(elem, "Power")
    start_fraction = low if low is not None else flat
    end_fraction = high if high is not None else flat
    start_watts = _to_watts(start_fraction, ftp_watts) if start_fraction is not None else None
    end_watts = _to_watts(end_fraction, ftp_watts) if end_fraction is not None else None
    end = cursor_seconds + round(duration)
    segments.append(WorkoutSegment(cursor_seconds, end, start_watts, end_watts))
    return end
