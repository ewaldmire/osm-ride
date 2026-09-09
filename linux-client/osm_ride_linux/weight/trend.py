"""Unit-agnostic trend-analysis helpers shared by weight and waist tracking - used identically for
both after each caller pre-converts to display units (lb/in), same as the chart itself.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/bodymetrics/BodyMetricsViewModel.kt and
TrendChart.kt's TrendPoint/rollingAverage/weeklyDelta.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..util import units
from .models import WaistEntry, WeightEntry

ROLLING_WINDOW_MILLIS = 7 * 24 * 60 * 60 * 1000
_DAY_MILLIS = 24 * 60 * 60 * 1000


@dataclass
class TrendPoint:
    epoch_millis: int
    value: float


def rolling_average(points: list[TrendPoint], window_millis: int = ROLLING_WINDOW_MILLIS) -> list[TrendPoint]:
    """points must be chronological (oldest first). Each output point is the average of every
    input point within window_millis before (and including) it - a trailing average, not
    centered, so it only ever needs data that's already been logged. O(n) two-pointer sliding
    window, not O(n^2) filter-per-point."""
    result: list[TrendPoint] = []
    window_start_index = 0
    window_sum = 0.0
    for i, point in enumerate(points):
        window_sum += point.value
        cutoff = point.epoch_millis - window_millis
        while points[window_start_index].epoch_millis <= cutoff:
            window_sum -= points[window_start_index].value
            window_start_index += 1
        window_count = i - window_start_index + 1
        result.append(TrendPoint(point.epoch_millis, window_sum / window_count))
    return result


@dataclass
class WeeklyDelta:
    current_avg: float
    previous_avg: float | None

    @property
    def delta(self) -> float | None:
        return None if self.previous_avg is None else self.current_avg - self.previous_avg


def weekly_delta(points: list[TrendPoint], now_millis: int) -> WeeklyDelta | None:
    current_window = [p for p in points if p.epoch_millis >= now_millis - 7 * _DAY_MILLIS]
    if not current_window:
        return None
    current_avg = sum(p.value for p in current_window) / len(current_window)
    previous_window_start = now_millis - 14 * _DAY_MILLIS
    previous_window_end = now_millis - 7 * _DAY_MILLIS
    previous_window = [p for p in points if previous_window_start <= p.epoch_millis < previous_window_end]
    previous_avg = sum(p.value for p in previous_window) / len(previous_window) if previous_window else None
    return WeeklyDelta(current_avg, previous_avg)


def weight_trend_points(entries: list[WeightEntry]) -> list[TrendPoint]:
    return [TrendPoint(e.recorded_at_epoch_millis, units.kg_to_lbs(e.weight_kg)) for e in entries]


def waist_trend_points(entries: list[WaistEntry]) -> list[TrendPoint]:
    return [TrendPoint(e.recorded_at_epoch_millis, units.cm_to_inches(e.waist_cm)) for e in entries]
