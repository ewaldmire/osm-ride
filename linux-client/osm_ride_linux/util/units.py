"""Formatting helpers - the app displays imperial units throughout (distance, weight), except
waist/neck/height, which are shown in centimeters per user preference even though they're stored
in the same unit anyway, so there's no conversion to do for those.

Mirrors app/src/main/java/com/ewaldmire/osmride/util/Units.kt.
"""

from __future__ import annotations

_METERS_PER_MILE = 1609.344
_METERS_PER_FOOT = 0.3048
_MPS_TO_MPH = 2.2369362921
_LB_PER_KG = 2.2046226218


def meters_to_miles(meters: float) -> float:
    return meters / _METERS_PER_MILE


def meters_to_feet(meters: float) -> float:
    return meters / _METERS_PER_FOOT


def mps_to_mph(meters_per_second: float) -> float:
    return meters_per_second * _MPS_TO_MPH


def kg_to_lbs(kg: float) -> float:
    return kg * _LB_PER_KG


def lbs_to_kg(lbs: float) -> float:
    return lbs / _LB_PER_KG


def format_miles(meters: float) -> str:
    return f"{meters_to_miles(meters):.2f} mi"


def format_feet(meters: float) -> str:
    return f"{meters_to_feet(meters):.0f} ft"


def format_mph(meters_per_second: float) -> str:
    return f"{mps_to_mph(meters_per_second):.1f} mph"


def format_watts(watts: float | None) -> str:
    return "--" if watts is None else f"{watts:.0f} W"


def format_cadence(rpm: float | None) -> str:
    return "--" if rpm is None else f"{rpm:.0f} rpm"


def format_heart_rate(bpm: float | int | None) -> str:
    return "--" if bpm is None else f"{bpm:.0f} bpm"


def format_grade(percent: float | None) -> str:
    return "--" if percent is None else f"{percent:+.1f}%"


def format_kilocalories(kcal: float | None) -> str:
    return "--" if kcal is None else f"{kcal:.0f} Cal"


def format_weight_lbs(kg: float) -> str:
    return f"{kg_to_lbs(kg):.1f} lb"


def format_waist_cm(cm: float) -> str:
    return f"{cm:.1f} cm"


def format_body_fat_percent(percent: float | None) -> str:
    return "--" if percent is None else f"{percent:.1f}%"


def format_duration(total_seconds: float) -> str:
    total_seconds = int(total_seconds)
    h = total_seconds // 3600
    m = (total_seconds % 3600) // 60
    s = total_seconds % 60
    if h > 0:
        return f"{h}:{m:02d}:{s:02d}"
    return f"{m}:{s:02d}"
