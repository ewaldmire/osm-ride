"""Turns live trainer/HR samples into progress along a preloaded route: cumulative distance, an
interpolated avatar position + bearing, live/average stats, and a recorded track for export.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/RideEngine.kt. One instance is used per
ride attempt. Uses plain callbacks (on_stats_changed) instead of Kotlin's StateFlow - the GTK UI
subscribes the same way it does to the BLE layer.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import replace

from ..ble.models import HeartRateSample, TrainerSample
from ..route.models import Route
from ..util.haversine import bearing_degrees
from .models import RecordedTrackPoint, RidePosition, RideState, RideStats, Workout

_GRADE_WINDOW_METERS = 30.0
_RECORD_INTERVAL_SECONDS = 1.0


class RideEngine:
    def __init__(self, route: Route) -> None:
        self.route = route
        # Optional structured (ERG-mode) workout riding alongside the route. Settable up until
        # start() is first called; the route's visuals (map/avatar/distance) are unaffected
        # either way - only which FTMS control mode gets driven (grade-sim vs target-power)
        # changes.
        self.workout: Workout | None = None

        self.stats = RideStats(total_distance_meters=route.total_distance_meters)
        self.on_stats_changed: Callable[[RideStats], None] | None = None

        self._recorded_points: list[RecordedTrackPoint] = []

        self._distance_meters = 0.0
        self._ftms_baseline_distance: float | None = None
        self._using_ftms_distance = False
        self._last_sample_timestamp: float | None = None
        self._last_recorded_timestamp = 0.0

        self._elapsed_seconds = 0.0
        self._last_tick_timestamp: float | None = None

        self._speed_sum = 0.0
        self._speed_samples = 0
        self._power_sum = 0.0
        self._power_samples = 0
        self._cadence_sum = 0.0
        self._cadence_samples = 0
        self._hr_sum = 0.0
        self._hr_samples = 0

        self._latest_speed = 0.0
        self._latest_cadence: float | None = None
        self._latest_power: int | None = None
        self._latest_heart_rate: int | None = None

    def track_points_snapshot(self) -> list[RecordedTrackPoint]:
        return list(self._recorded_points)

    def start(self) -> None:
        """Starts a fresh ride, or resumes one paused with pause()."""
        if self.stats.state in (RideState.IDLE, RideState.PAUSED):
            self._last_tick_timestamp = time.time()
            self._last_sample_timestamp = None  # avoid a huge distance jump from the paused gap
            self._set_stats(replace(self.stats, state=RideState.RIDING))

    def pause(self) -> None:
        if self.stats.state == RideState.RIDING:
            self._last_tick_timestamp = None
            self._set_stats(replace(self.stats, state=RideState.PAUSED))

    def finish_manually(self) -> None:
        """Ends the ride early, before the route distance is completed."""
        if self.stats.state in (RideState.RIDING, RideState.PAUSED):
            self._last_tick_timestamp = None
            self._set_stats(replace(self.stats, state=RideState.FINISHED))

    def on_trainer_sample(self, sample: TrainerSample) -> None:
        if self.stats.state != RideState.RIDING:
            return
        now = sample.timestamp

        # The trainer's own cumulative distance counter (FTMS) is more accurate than
        # integrating instantaneous speed across noisy BLE notification gaps, so prefer it.
        if sample.total_distance_meters is not None:
            if self._ftms_baseline_distance is None:
                self._ftms_baseline_distance = sample.total_distance_meters
            self._using_ftms_distance = True
            delta = sample.total_distance_meters - (self._ftms_baseline_distance or 0.0)
            self._distance_meters = max(self._distance_meters, delta)
        elif not self._using_ftms_distance:
            prev_ts = self._last_sample_timestamp
            speed = sample.speed_mps
            if prev_ts is not None and speed is not None:
                dt_seconds = now - prev_ts
                if 0.0 <= dt_seconds <= 5.0:  # ignore gaps from reconnects etc.
                    self._distance_meters += speed * dt_seconds
        self._last_sample_timestamp = now

        if sample.speed_mps is not None:
            self._latest_speed = sample.speed_mps
            self._speed_sum += sample.speed_mps
            self._speed_samples += 1
        if sample.power_watts is not None:
            self._latest_power = sample.power_watts
            self._power_sum += sample.power_watts
            self._power_samples += 1
        if sample.cadence_rpm is not None:
            self._latest_cadence = sample.cadence_rpm
            self._cadence_sum += sample.cadence_rpm
            self._cadence_samples += 1

        self._record_point_if_due(now)
        self._publish_stats()

    def on_heart_rate_sample(self, sample: HeartRateSample) -> None:
        self._latest_heart_rate = sample.bpm
        self._hr_sum += sample.bpm
        self._hr_samples += 1
        if self.stats.state == RideState.RIDING:
            self._publish_stats()

    def on_clock_tick(self) -> None:
        """Call roughly once a second so elapsed time keeps moving between trainer notifications."""
        if self.stats.state != RideState.RIDING:
            return
        now = time.time()
        if self._last_tick_timestamp is not None:
            self._elapsed_seconds += now - self._last_tick_timestamp
        self._last_tick_timestamp = now
        self._publish_stats()

    def _record_point_if_due(self, now: float) -> None:
        if now - self._last_recorded_timestamp < _RECORD_INTERVAL_SECONDS:
            return
        self._last_recorded_timestamp = now
        position = self._position_at(self._distance_meters)
        self._recorded_points.append(
            RecordedTrackPoint(
                timestamp=now,
                lat=position.lat,
                lon=position.lon,
                elevation_meters=position.elevation_meters,
                heart_rate_bpm=self._latest_heart_rate,
                cadence_rpm=self._latest_cadence,
            )
        )

    def _publish_stats(self) -> None:
        total = self.route.total_distance_meters
        clamped = max(0.0, min(self._distance_meters, total))
        finished = total > 0 and clamped >= total
        position = self._position_at(clamped)

        self._set_stats(
            RideStats(
                state=RideState.FINISHED if finished else RideState.RIDING,
                distance_meters=clamped,
                total_distance_meters=total,
                progress_fraction=(clamped / total) if total > 0 else 0.0,
                position=position,
                elapsed_seconds=self._elapsed_seconds,
                current_speed_mps=self._latest_speed,
                current_cadence_rpm=self._latest_cadence,
                current_power_watts=self._latest_power,
                current_heart_rate_bpm=self._latest_heart_rate,
                current_grade_percent=self._grade_at(clamped),
                current_target_watts=self._target_watts_at(self._elapsed_seconds),
                avg_speed_mps=(clamped / self._elapsed_seconds) if self._elapsed_seconds > 0 else 0.0,
                avg_power_watts=(self._power_sum / self._power_samples) if self._power_samples > 0 else None,
                avg_cadence_rpm=(self._cadence_sum / self._cadence_samples)
                if self._cadence_samples > 0
                else None,
                avg_heart_rate_bpm=(self._hr_sum / self._hr_samples) if self._hr_samples > 0 else None,
            )
        )
        if finished:
            self._last_tick_timestamp = None

    def _set_stats(self, stats: RideStats) -> None:
        self.stats = stats
        if self.on_stats_changed:
            self.on_stats_changed(stats)

    def _grade_at(self, distance: float) -> float | None:
        """Average grade (%) over a short window centered on distance, for the trainer's
        simulated resistance and the ride screen's grade readout. None if the route has no
        elevation data."""
        if len(self.route.points) < 2:
            return None
        total = self.route.total_distance_meters
        ahead = min(distance + _GRADE_WINDOW_METERS, total)
        behind = max(distance - _GRADE_WINDOW_METERS, 0.0)
        run = ahead - behind
        if run <= 0:
            return None
        ahead_elevation = self._position_at(ahead).elevation_meters
        behind_elevation = self._position_at(behind).elevation_meters
        if ahead_elevation is None or behind_elevation is None:
            return None
        return (ahead_elevation - behind_elevation) / run * 100.0

    def _target_watts_at(self, elapsed_seconds: float) -> int | None:
        """Interpolated ERG target power at elapsed_seconds into the ride, from the attached
        workout's timeline. None with no workout attached, past its end, or during a
        free-ride/max-effort segment (no fixed target for that stretch)."""
        segments = self.workout.segments if self.workout else None
        if not segments:
            return None
        seg = next(
            (s for s in segments if s.start_seconds <= elapsed_seconds < s.end_seconds),
            None,
        )
        if seg is None:
            last = segments[-1]
            seg = last if elapsed_seconds >= last.end_seconds else segments[0]
        if seg.start_watts is None or seg.end_watts is None:
            return None
        span = max(seg.end_seconds - seg.start_seconds, 1)
        t = min(max((elapsed_seconds - seg.start_seconds) / span, 0.0), 1.0)
        return round(seg.start_watts + (seg.end_watts - seg.start_watts) * t)

    def _position_at(self, distance: float) -> RidePosition:
        """Binary search + linear interpolation of lat/lon/elevation/bearing at distance along
        the route."""
        points = self.route.points
        if not points:
            return RidePosition(0.0, 0.0, None, 0.0)
        if len(points) == 1 or distance <= points[0].cumulative_distance_meters:
            p = points[0]
            bearing = bearing_degrees(p.lat, p.lon, points[1].lat, points[1].lon) if len(points) > 1 else 0.0
            return RidePosition(p.lat, p.lon, p.elevation_meters, bearing)
        if distance >= points[-1].cumulative_distance_meters:
            p = points[-1]
            prev = points[-2]
            return RidePosition(
                p.lat, p.lon, p.elevation_meters, bearing_degrees(prev.lat, prev.lon, p.lat, p.lon)
            )

        lo, hi = 0, len(points) - 1
        while lo < hi - 1:
            mid = (lo + hi) // 2
            if points[mid].cumulative_distance_meters <= distance:
                lo = mid
            else:
                hi = mid
        a, b = points[lo], points[hi]
        segment_length = b.cumulative_distance_meters - a.cumulative_distance_meters
        t = min(max((distance - a.cumulative_distance_meters) / segment_length, 0.0), 1.0) if segment_length > 0 else 0.0
        lat = a.lat + (b.lat - a.lat) * t
        lon = a.lon + (b.lon - a.lon) * t
        elevation = (
            a.elevation_meters + (b.elevation_meters - a.elevation_meters) * t
            if a.elevation_meters is not None and b.elevation_meters is not None
            else None
        )
        return RidePosition(lat, lon, elevation, bearing_degrees(a.lat, a.lon, b.lat, b.lon))
