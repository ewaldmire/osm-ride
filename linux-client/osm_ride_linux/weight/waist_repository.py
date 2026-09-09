"""Persists logged waist entries to local storage, same JSON-index pattern as
weight/repository.py (deliberately duplicated rather than shared - see WaistRepository.kt).

Mirrors app/src/main/java/com/ewaldmire/osmride/weight/WaistRepository.kt.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from collections.abc import Callable
from dataclasses import asdict
from pathlib import Path

from .models import WaistEntry


def _data_home() -> Path:
    xdg = os.environ.get("XDG_DATA_HOME")
    base = Path(xdg) if xdg else Path.home() / ".local" / "share"
    return base / "osm-ride-linux"


class WaistRepository:
    def __init__(self, data_dir: Path | None = None) -> None:
        self._waist_dir = (data_dir or _data_home()) / "waist"
        self._waist_dir.mkdir(parents=True, exist_ok=True)
        self._index_file = self._waist_dir / "index.json"

        self.on_entries_changed: Callable[[list[WaistEntry]], None] | None = None
        # Newest first.
        self.entries: list[WaistEntry] = self._load_index()

    def add_entry(self, waist_cm: float, recorded_at_epoch_millis: int | None = None) -> WaistEntry:
        entry = WaistEntry(
            id=str(uuid.uuid4()),
            waist_cm=waist_cm,
            recorded_at_epoch_millis=recorded_at_epoch_millis or int(time.time() * 1000),
        )
        updated = sorted([*self.entries, entry], key=lambda e: e.recorded_at_epoch_millis, reverse=True)
        self._update_entries(updated)
        return entry

    def delete_entry(self, entry_id: str) -> None:
        updated = [e for e in self.entries if e.id != entry_id]
        self._update_entries(updated)

    def _update_entries(self, updated: list[WaistEntry]) -> None:
        self.entries = updated
        self._save_index(updated)
        if self.on_entries_changed:
            self.on_entries_changed(updated)

    def _load_index(self) -> list[WaistEntry]:
        if not self._index_file.exists():
            return []
        try:
            raw = json.loads(self._index_file.read_text(encoding="utf-8"))
            return [WaistEntry(**entry) for entry in raw]
        except (json.JSONDecodeError, KeyError, TypeError):
            return []

    def _save_index(self, entries: list[WaistEntry]) -> None:
        self._index_file.write_text(json.dumps([asdict(e) for e in entries], indent=2), encoding="utf-8")
