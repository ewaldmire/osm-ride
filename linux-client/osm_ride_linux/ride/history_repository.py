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

    def save_ride(self, route_name: str, stats: RideStats, gpx_content: str) -> RideRecord:
        record_id = str(uuid.uuid4())
        file_name = f"{record_id}.gpx"
        (self._rides_dir / file_name).write_text(gpx_content, encoding="utf-8")

        record = RideRecord(
            id=record_id,
            route_name=route_name,
            title=route_name,
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

    def update_ride(self, ride_id: str, title: str, notes: str) -> None:
        """Lets the rider rename a ride and add notes after the fact - useful when they ride the
        same route regularly and want to tell repeat rides of it apart in history."""
        updated = [replace(r, title=title, notes=notes) if r.id == ride_id else r for r in self.rides]
        self._update_rides(updated)

    def gpx_file(self, record: RideRecord) -> Path:
        return self._rides_dir / record.gpx_file_name

    def delete_ride(self, ride_id: str) -> None:
        record = next((r for r in self.rides if r.id == ride_id), None)
        if record is None:
            return
        (self._rides_dir / record.gpx_file_name).unlink(missing_ok=True)
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
