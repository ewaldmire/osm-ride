"""Mirrors app/src/main/java/com/ewaldmire/osmride/weight/WeightEntry.kt and WaistEntry.kt."""

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


@dataclass
class WaistEntry:
    """A single logged waist measurement - the primary "visible abs" signal, alongside weight.
    Stored in cm for the same metric-internal/imperial-display reason as WeightEntry."""

    id: str
    waist_cm: float
    recorded_at_epoch_millis: int
