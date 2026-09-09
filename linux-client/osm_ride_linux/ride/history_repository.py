"""Persists completed rides (GPX + summary) to local storage for the history screen.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/RideHistoryRepository.kt.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from collections.abc import Callable
from dataclasses import asdict, replace
from pathlib import Path

from .models import RideRecord, RideStats


def _data_home() -> Path:
    xdg = os.environ.get("XDG_DATA_HOME")
    base = Path(xdg) if xdg else Path.home() / ".local" / "share"
    return base / "osm-ride-linux"


class RideHistoryRepository:
    def __init__(self, data_dir: Path | None = None) -> None:
        self._rides_dir = (data_dir or _data_home()) / "rides"
        self._rides_dir.mkdir(parents=True, exist_ok=True)
        self._index_file = self._rides_dir / "index.json"

        self.on_rides_changed: Callable[[list[RideRecord]], None] | None = None
        # Newest first.
        self.rides: list[RideRecord] = self._load_index()

    def save_ride(self, route_name: str, route_id: str | None, stats: RideStats, gpx_content: str) -> RideRecord:
        record_id = str(uuid.uuid4())
        file_name = f"{record_id}.gpx"
        (self._rides_dir / file_name).write_text(gpx_content, encoding="utf-8")

        record = RideRecord(
            id=record_id,
            route_name=route_name,
            title=route_name,
            route_id=route_id,
            completed_at_epoch_millis=int(time.time() * 1000),
            distance_meters=stats.distance_meters,
            duration_seconds=stats.elapsed_seconds,
            avg_speed_mps=stats.avg_speed_mps,
            avg_power_watts=stats.avg_power_watts,
            avg_cadence_rpm=stats.avg_cadence_rpm,
            avg_heart_rate_bpm=stats.avg_heart_rate_bpm,
            estimated_kilocalories=stats.estimated_kilocalories,
            gpx_file_name=file_name,
        )
        self._update_rides([record, *self.rides])
        return record

    def import_ride(
        self,
        title: str,
        completed_at_epoch_millis: int,
        distance_meters: float,
        duration_seconds: float,
        avg_speed_mps: float,
        avg_power_watts: float | None,
        avg_cadence_rpm: float | None,
        avg_heart_rate_bpm: float | None,
        estimated_kilocalories: float | None,
        gpx_content: str,
    ) -> RideRecord:
        """Saves an outdoor ride imported from a .fit file - same storage shape as a
        live-recorded ride, just with no route_id (there's no in-app route it was ridden against)
        and a caller-supplied completion time (the ride's own recorded time, not "now"). See
        fit_parser/history_view.py's import_ride."""
        record_id = str(uuid.uuid4())
        file_name = f"{record_id}.gpx"
        (self._rides_dir / file_name).write_text(gpx_content, encoding="utf-8")

        record = RideRecord(
            id=record_id,
            route_name=title,
            title=title,
            route_id=None,
            completed_at_epoch_millis=completed_at_epoch_millis,
            distance_meters=distance_meters,
            duration_seconds=duration_seconds,
            avg_speed_mps=avg_speed_mps,
            avg_power_watts=avg_power_watts,
            avg_cadence_rpm=avg_cadence_rpm,
            avg_heart_rate_bpm=avg_heart_rate_bpm,
            estimated_kilocalories=estimated_kilocalories,
            gpx_file_name=file_name,
        )
        updated = sorted([record, *self.rides], key=lambda r: r.completed_at_epoch_millis, reverse=True)
        self._update_rides(updated)
        return record

    def update_ride(self, ride_id: str, title: str, notes: str) -> None:
        """Lets the rider rename a ride and add notes after the fact - useful when they ride the
        same route regularly and want to tell repeat rides of it apart in history."""
        updated = [replace(r, title=title, notes=notes) if r.id == ride_id else r for r in self.rides]
        self._update_rides(updated)

    def gpx_file(self, record: RideRecord) -> Path:
        return self._rides_dir / record.gpx_file_name

    def set_thumbnail(self, ride_id: str, thumbnail_file_name: str) -> None:
        """Called once route_thumbnail_generator finishes snapshotting this ride's own recorded
        track - never blocks save_ride, since generation happens asynchronously afterward."""
        updated = [
            replace(r, thumbnail_file_name=thumbnail_file_name) if r.id == ride_id else r for r in self.rides
        ]
        self._update_rides(updated)

    def thumbnail_path(self, record: RideRecord) -> Path | None:
        if record.thumbnail_file_name is None:
            return None
        return self._rides_dir / record.thumbnail_file_name

    @property
    def directory(self) -> Path:
        return self._rides_dir

    def delete_ride(self, ride_id: str) -> None:
        record = next((r for r in self.rides if r.id == ride_id), None)
        if record is None:
            return
        (self._rides_dir / record.gpx_file_name).unlink(missing_ok=True)
        thumb_path = self.thumbnail_path(record)
        if thumb_path is not None:
            thumb_path.unlink(missing_ok=True)
        self._update_rides([r for r in self.rides if r.id != ride_id])

    def _update_rides(self, updated: list[RideRecord]) -> None:
        self.rides = updated
        self._save_index(updated)
        if self.on_rides_changed:
            self.on_rides_changed(updated)

    def _load_index(self) -> list[RideRecord]:
        if not self._index_file.exists():
            return []
        try:
            raw = json.loads(self._index_file.read_text(encoding="utf-8"))
            return [RideRecord(**entry) for entry in raw]
        except (json.JSONDecodeError, KeyError, TypeError):
            return []

    def _save_index(self, records: list[RideRecord]) -> None:
        self._index_file.write_text(json.dumps([asdict(r) for r in records], indent=2), encoding="utf-8")
