"""Imports and persists structured (ERG-mode) workouts: .erg (absolute watts), .mrc (%FTP), and
.zwo (Zwift XML, %FTP). Workout segment lists are small, so unlike routes the full parsed
workout is kept directly in the index rather than needing a separate lazy-loaded file per
workout.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/WorkoutRepository.kt.
"""

from __future__ import annotations

import json
import os
import uuid
from collections.abc import Callable
from dataclasses import asdict, replace
from pathlib import Path

from . import erg_parser, zwo_parser
from .models import Workout, WorkoutSegment


class WorkoutRepositoryError(Exception):
    pass


def _data_home() -> Path:
    xdg = os.environ.get("XDG_DATA_HOME")
    base = Path(xdg) if xdg else Path.home() / ".local" / "share"
    return base / "osm-ride-linux"


class WorkoutRepository:
    def __init__(self, data_dir: Path | None = None) -> None:
        self._workouts_dir = (data_dir or _data_home()) / "workouts"
        self._workouts_dir.mkdir(parents=True, exist_ok=True)
        self._index_file = self._workouts_dir / "index.json"

        self.on_workouts_changed: Callable[[list[Workout]], None] | None = None
        self.workouts: list[Workout] = self._load_index()

    def import_workout(self, source_path: Path, display_name: str | None, ftp_watts: int | None) -> Workout:
        text = source_path.read_text(encoding="utf-8")
        lower_name = (display_name or "").lower()
        fallback_name = (
            (Path(display_name).stem.strip() if display_name else "") or "Imported Workout"
        )

        if lower_name.endswith(".mrc"):
            parsed = erg_parser.parse(text, is_percent_based=True, ftp_watts=ftp_watts, fallback_name=fallback_name)
        elif lower_name.endswith(".zwo"):
            parsed = zwo_parser.parse(text, ftp_watts=ftp_watts, fallback_name=fallback_name)
        else:
            # .erg, or unrecognized extension - assume the common case, absolute watts.
            parsed = erg_parser.parse(text, is_percent_based=False, ftp_watts=ftp_watts, fallback_name=fallback_name)

        if not parsed.segments:
            raise WorkoutRepositoryError("Workout file has no usable intervals")

        workout = Workout(
            id=str(uuid.uuid4()),
            name=parsed.name,
            segments=parsed.segments,
            total_duration_seconds=parsed.total_duration_seconds,
        )
        self._update_workouts([*self.workouts, workout])
        return workout

    def get_workout(self, workout_id: str) -> Workout | None:
        return next((w for w in self.workouts if w.id == workout_id), None)

    def save_created_workout(
        self, existing_id: str | None, name: str, segments: list[WorkoutSegment]
    ) -> Workout:
        """Saves a workout built (or edited) in-app via the block-based workout creator. Pass
        existing_id when re-editing an already-saved workout so it updates in place."""
        if not segments:
            raise WorkoutRepositoryError("Workout has no intervals")
        workout_id = existing_id or str(uuid.uuid4())
        workout = Workout(
            id=workout_id,
            name=name.strip() or "New Workout",
            segments=segments,
            total_duration_seconds=max(s.end_seconds for s in segments),
        )
        exists = any(w.id == workout_id for w in self.workouts)
        if exists:
            updated = [workout if w.id == workout_id else w for w in self.workouts]
        else:
            updated = [*self.workouts, workout]
        self._update_workouts(updated)
        return workout

    def rename_workout(self, workout_id: str, name: str) -> None:
        resolved = name.strip()
        if not resolved:
            return
        updated = [replace(w, name=resolved) if w.id == workout_id else w for w in self.workouts]
        self._update_workouts(updated)

    def delete_workout(self, workout_id: str) -> None:
        self._update_workouts([w for w in self.workouts if w.id != workout_id])

    def _update_workouts(self, updated: list[Workout]) -> None:
        self.workouts = updated
        self._save_index(updated)
        if self.on_workouts_changed:
            self.on_workouts_changed(updated)

    def _load_index(self) -> list[Workout]:
        if not self._index_file.exists():
            return []
        try:
            raw = json.loads(self._index_file.read_text(encoding="utf-8"))
            return [_workout_from_dict(entry) for entry in raw]
        except (json.JSONDecodeError, KeyError, TypeError):
            return []

    def _save_index(self, workouts: list[Workout]) -> None:
        self._index_file.write_text(json.dumps([asdict(w) for w in workouts], indent=2), encoding="utf-8")


def _workout_from_dict(d: dict) -> Workout:
    return Workout(
        id=d["id"],
        name=d["name"],
        segments=[WorkoutSegment(**seg) for seg in d["segments"]],
        total_duration_seconds=d["total_duration_seconds"],
    )
