"""Mirrors app/src/main/java/com/ewaldmire/osmride/route/Route.kt and BRouterClient.kt's
RouteWaypoint."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RoutePoint:
    lat: float
    lon: float
    elevation_meters: float | None
    # Distance in meters from the start of the route to this point, along the track.
    cumulative_distance_meters: float


@dataclass(frozen=True)
class Route:
    id: str
    name: str
    points: list[RoutePoint]
    total_distance_meters: float
    elevation_gain_meters: float


@dataclass(frozen=True)
class RouteWaypoint:
    """A tapped point placed by the user while building a route in-app."""

    lat: float
    lon: float


@dataclass
class RouteSummary:
    """Lightweight, persisted index entry - avoids re-parsing every GPX just to show the list."""

    id: str
    name: str
    file_name: str
    total_distance_meters: float
    elevation_gain_meters: float
    imported_at_epoch_millis: int
    # Non-None only for routes built in-app with the route creator; used to reopen them for
    # editing.
    waypoints: list[RouteWaypoint] | None = None
