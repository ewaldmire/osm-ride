"""Mirrors app/src/main/java/com/ewaldmire/osmride/weight/WeightEntry.kt."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass
class WeightEntry:
    """A single logged body-weight measurement. Stored in kg (the app displays imperial units
    throughout, per util/units.py, but keeps metric internally - same convention as route
    distances)."""

    id: str
    weight_kg: float
    recorded_at_epoch_millis: int
