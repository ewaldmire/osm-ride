"""Persists logged body-weight entries to local storage, same JSON-index pattern as
route/repository.py and ride/history_repository.py.

Mirrors app/src/main/java/com/ewaldmire/osmride/weight/WeightRepository.kt.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from collections.abc import Callable
from dataclasses import asdict
from pathlib import Path

from .models import WeightEntry


def _data_home() -> Path:
    xdg = os.environ.get("XDG_DATA_HOME")
    base = Path(xdg) if xdg else Path.home() / ".local" / "share"
    return base / "osm-ride-linux"


class WeightRepository:
    def __init__(self, data_dir: Path | None = None) -> None:
        self._weight_dir = (data_dir or _data_home()) / "weight"
        self._weight_dir.mkdir(parents=True, exist_ok=True)
        self._index_file = self._weight_dir / "index.json"

        self.on_entries_changed: Callable[[list[WeightEntry]], None] | None = None
        # Newest first.
        self.entries: list[WeightEntry] = self._load_index()

    def add_entry(self, weight_kg: float, recorded_at_epoch_millis: int | None = None) -> WeightEntry:
        entry = WeightEntry(
            id=str(uuid.uuid4()),
            weight_kg=weight_kg,
            recorded_at_epoch_millis=recorded_at_epoch_millis or int(time.time() * 1000),
        )
        updated = sorted([*self.entries, entry], key=lambda e: e.recorded_at_epoch_millis, reverse=True)
        self._update_entries(updated)
        return entry

    def delete_entry(self, entry_id: str) -> None:
        updated = [e for e in self.entries if e.id != entry_id]
        self._update_entries(updated)

    def _update_entries(self, updated: list[WeightEntry]) -> None:
        self.entries = updated
        self._save_index(updated)
        if self.on_entries_changed:
            self.on_entries_changed(updated)

    def _load_index(self) -> list[WeightEntry]:
        if not self._index_file.exists():
            return []
        try:
            raw = json.loads(self._index_file.read_text(encoding="utf-8"))
            return [WeightEntry(**entry) for entry in raw]
        except (json.JSONDecodeError, KeyError, TypeError):
            return []

    def _save_index(self, entries: list[WeightEntry]) -> None:
        self._index_file.write_text(json.dumps([asdict(e) for e in entries], indent=2), encoding="utf-8")
