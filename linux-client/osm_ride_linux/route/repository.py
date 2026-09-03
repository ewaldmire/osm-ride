"""Imports GPX routes into a local data directory and keeps a small JSON index for the list
screen. Mirrors app/src/main/java/com/ewaldmire/osmride/route/RouteRepository.kt.

Storage lives under $XDG_DATA_HOME/osm-ride-linux/routes (or ~/.local/share/... if unset) rather
than Android's app-private Context.filesDir - the closest Linux equivalent for "this app's own
data, not meant to be browsed by the user directly."
"""

from __future__ import annotations

import json
import os
import time
import uuid
from collections.abc import Callable
from dataclasses import asdict, replace
from pathlib import Path

from . import gpx as gpx_module
from .models import Route, RouteSummary, RouteWaypoint


def _data_home() -> Path:
    xdg = os.environ.get("XDG_DATA_HOME")
    base = Path(xdg) if xdg else Path.home() / ".local" / "share"
    return base / "osm-ride-linux"


class RouteRepositoryError(Exception):
    pass


class RouteRepository:
    def __init__(self, data_dir: Path | None = None) -> None:
        self._routes_dir = (data_dir or _data_home()) / "routes"
        self._routes_dir.mkdir(parents=True, exist_ok=True)
        self._index_file = self._routes_dir / "index.json"

        self.on_routes_changed: Callable[[list[RouteSummary]], None] | None = None
        self.routes: list[RouteSummary] = self._load_index()

    def import_gpx(self, source_path: Path, display_name_hint: str | None) -> RouteSummary:
        route_id = str(uuid.uuid4())
        dest_file = self._routes_dir / f"{route_id}.gpx"
        gpx_text = source_path.read_text(encoding="utf-8")
        dest_file.write_text(gpx_text, encoding="utf-8")

        parsed = gpx_module.parse(gpx_text)
        if len(parsed.points) < 2:
            dest_file.unlink(missing_ok=True)
            raise RouteRepositoryError("GPX file has no usable track points")

        name = (parsed.name or "").strip() or (display_name_hint or "").strip() or "Imported Route"

        summary = RouteSummary(
            id=route_id,
            name=name,
            file_name=dest_file.name,
            total_distance_meters=parsed.total_distance_meters,
            elevation_gain_meters=parsed.elevation_gain_meters,
            imported_at_epoch_millis=int(time.time() * 1000),
        )
        self._update_routes([*self.routes, summary])
        return summary

    def load_route(self, route_id: str) -> Route | None:
        summary = next((r for r in self.routes if r.id == route_id), None)
        if summary is None:
            return None
        file_path = self._routes_dir / summary.file_name
        if not file_path.exists():
            return None
        parsed = gpx_module.parse(file_path.read_text(encoding="utf-8"))
        return Route(
            id=summary.id,
            name=summary.name,
            points=parsed.points,
            total_distance_meters=parsed.total_distance_meters,
            elevation_gain_meters=parsed.elevation_gain_meters,
        )

    def get_route_summary(self, route_id: str) -> RouteSummary | None:
        return next((r for r in self.routes if r.id == route_id), None)

    def rename_route(self, route_id: str, name: str) -> None:
        resolved = name.strip()
        if not resolved:
            return
        updated = [replace(r, name=resolved) if r.id == route_id else r for r in self.routes]
        self._update_routes(updated)

    def delete_route(self, route_id: str) -> None:
        summary = self.get_route_summary(route_id)
        if summary is None:
            return
        (self._routes_dir / summary.file_name).unlink(missing_ok=True)
        self._update_routes([r for r in self.routes if r.id != route_id])

    def save_created_route(
        self,
        existing_id: str | None,
        name: str,
        gpx_text: str,
        waypoints: list[RouteWaypoint],
    ) -> RouteSummary:
        """Saves a route built (or edited) in-app via the route creator. Pass existing_id when
        re-routing an already-created route so it updates in place instead of duplicating."""
        route_id = existing_id or str(uuid.uuid4())
        dest_file = self._routes_dir / f"{route_id}.gpx"
        dest_file.write_text(gpx_text, encoding="utf-8")

        parsed = gpx_module.parse(gpx_text)
        if len(parsed.points) < 2:
            dest_file.unlink(missing_ok=True)
            raise RouteRepositoryError("Route has no usable track points")

        existing = self.get_route_summary(route_id)
        summary = RouteSummary(
            id=route_id,
            name=name.strip() or "New Route",
            file_name=dest_file.name,
            total_distance_meters=parsed.total_distance_meters,
            elevation_gain_meters=parsed.elevation_gain_meters,
            imported_at_epoch_millis=existing.imported_at_epoch_millis if existing else int(time.time() * 1000),
            waypoints=waypoints,
        )
        if existing is not None:
            updated = [summary if r.id == route_id else r for r in self.routes]
        else:
            updated = [*self.routes, summary]
        self._update_routes(updated)
        return summary

    def _update_routes(self, updated: list[RouteSummary]) -> None:
        self.routes = updated
        self._save_index(updated)
        if self.on_routes_changed:
            self.on_routes_changed(updated)

    def _load_index(self) -> list[RouteSummary]:
        if not self._index_file.exists():
            return []
        try:
            raw = json.loads(self._index_file.read_text(encoding="utf-8"))
            return [_route_summary_from_dict(entry) for entry in raw]
        except (json.JSONDecodeError, KeyError, TypeError):
            return []

    def _save_index(self, routes: list[RouteSummary]) -> None:
        self._index_file.write_text(
            json.dumps([_route_summary_to_dict(r) for r in routes], indent=2), encoding="utf-8"
        )


def _route_summary_to_dict(summary: RouteSummary) -> dict:
    d = asdict(summary)
    return d


def _route_summary_from_dict(d: dict) -> RouteSummary:
    waypoints = d.get("waypoints")
    return RouteSummary(
        id=d["id"],
        name=d["name"],
        file_name=d["file_name"],
        total_distance_meters=d["total_distance_meters"],
        elevation_gain_meters=d["elevation_gain_meters"],
        imported_at_epoch_millis=d["imported_at_epoch_millis"],
        waypoints=[RouteWaypoint(**wp) for wp in waypoints] if waypoints else None,
    )
