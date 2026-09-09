"""Mirrors app/src/main/java/com/ewaldmire/osmride/weight/NavyBodyFat.kt."""

from __future__ import annotations

import math


def estimate_percent(waist_cm: float, neck_cm: float, height_cm: float) -> float | None:
    """Navy method body-fat % estimate (metric, male-only - this app is scoped to a single user).
    Returns None for physically nonsensical inputs (waist <= neck, non-positive denominator)."""
    waist_minus_neck = waist_cm - neck_cm
    if waist_minus_neck <= 0 or height_cm <= 0:
        return None
    denominator = 1.0324 - 0.19077 * math.log10(waist_minus_neck) + 0.15456 * math.log10(height_cm)
    if denominator <= 0:
        return None
    return 495.0 / denominator - 450.0
