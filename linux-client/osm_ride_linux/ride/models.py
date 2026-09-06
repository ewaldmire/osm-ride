"""Mirrors app/src/main/java/com/ewaldmire/osmride/ride/{RideEngine,Workout,RideRecord}.kt.

Timestamps/durations use seconds throughout (Python's native time.time() unit), unlike the
Kotlin originals' milliseconds - a deliberate unit change, not a behavior change; every place
that divided by 1000 in Kotlin simply doesn't need to here.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, auto


class RideState(Enum):
    IDLE = auto()
    RIDING = auto()
    PAUSED = auto()
    FINISHED = auto()


@dataclass(frozen=True)
class RidePosition:
    lat: float
    lon: float
    elevation_meters: float | None
    bearing_degrees: float


@dataclass(frozen=True)
class RideStats:
    state: RideState = RideState.IDLE
    distance_meters: float = 0.0
    total_distance_meters: float = 0.0
    progress_fraction: float = 0.0
    position: RidePosition | None = None
    elapsed_seconds: float = 0.0
    current_speed_mps: float = 0.0
    current_cadence_rpm: float | None = None
    current_power_watts: int | None = None
    current_heart_rate_bpm: int | None = None
    # Average grade, as a percent, over a short window around the current position.
    current_grade_percent: float | None = None
    # Non-None while a workout is attached and the current point is within a defined power
    # segment (None for a free-ride/max-effort stretch, or once the workout has ended).
    current_target_watts: int | None = None
    avg_speed_mps: float = 0.0
    avg_power_watts: float | None = None
    avg_cadence_rpm: float | None = None
    avg_heart_rate_bpm: float | None = None

    @property
    def estimated_kilocalories(self) -> float | None:
        """Rough estimate from mechanical work (avg power x duration) at ~24% gross cycling
        efficiency, which conveniently makes kcal ~= kJ of work. None without power data to
        compute it from (e.g. a CSC-only trainer with no power meter)."""
        if self.avg_power_watts is None:
            return None
        return self.avg_power_watts * self.elapsed_seconds / 1000.0


@dataclass(frozen=True)
class RecordedTrackPoint:
    timestamp: float
    lat: float
    lon: float
    elevation_meters: float | None
    heart_rate_bpm: int | None
    cadence_rpm: float | None


@dataclass(frozen=True)
class WorkoutSegment:
    """One linear power ramp from start_seconds to end_seconds. A flat interval has
    start_watts == end_watts. None watts means "free ride"/"max effort" - no ERG target is sent
    to the trainer for that stretch."""

    start_seconds: float
    end_seconds: float
    start_watts: int | None
    end_watts: int | None


@dataclass
class Workout:
    """A structured (ERG-mode) workout: a target-power timeline, independent of any route."""

    id: str
    name: str
    segments: list[WorkoutSegment] = field(default_factory=list)
    total_duration_seconds: float = 0.0


@dataclass(frozen=True)
class ParsedWorkout:
    """Parser output before an id has been assigned by the repository."""

    name: str
    segments: list[WorkoutSegment]
    total_duration_seconds: float


@dataclass
class RideRecord:
    """Persisted summary of a completed ride, shown in the ride history list."""

    id: str
    route_name: str
    completed_at_epoch_millis: int
    distance_meters: float
    duration_seconds: float
    avg_speed_mps: float
    avg_power_watts: float | None
    avg_cadence_rpm: float | None
    avg_heart_rate_bpm: float | None
    gpx_file_name: str
    title: str | None = None
    notes: str = ""
    estimated_kilocalories: float | None = None
    # None for rides saved before this field existed, and for rides whose route was later
    # deleted - either way, history falls back to a placeholder thumbnail.
    route_id: str | None = None

    def __post_init__(self) -> None:
        if self.title is None:
            self.title = self.route_name
