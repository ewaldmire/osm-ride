"""Elevation-vs-distance strip along the route, in the same visual language as
WorkoutProfileChart: a filled area under the elevation line, with a vertical marker for how far
along the route the rider currently is. Doubles as the ride's overall progress indicator (a
separate progress bar would be redundant with the marker line this already draws) - routes with
no elevation data (e.g. a GPX with no <ele> tags) fall back to a flat baseline so the marker is
still visible instead of the whole widget disappearing.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/ride/ElevationProfileChart.kt's Canvas
drawing, using Cairo (GTK's drawing API) instead of Compose's Canvas.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from ..route.models import Route  # noqa: E402


class ElevationProfileChart(Gtk.DrawingArea):
    def __init__(self) -> None:
        super().__init__()
        self._points: list[tuple[float, float]] = []  # (cumulative_distance_meters, elevation_meters)
        self._total_distance_meters = 1.0
        self._progress_meters: float | None = None
        self.set_size_request(-1, 22)
        self.set_draw_func(self._on_draw)

    def set_route(self, route: Route | None) -> None:
        if route is None:
            self._points = []
        else:
            self._points = [
                (p.cumulative_distance_meters, p.elevation_meters) for p in route.points if p.elevation_meters is not None
            ]
            self._total_distance_meters = max(route.total_distance_meters, 1.0)
        self.queue_draw()

    def set_progress_meters(self, meters: float | None) -> None:
        self._progress_meters = meters
        self.queue_draw()

    def _on_draw(self, _area: Gtk.DrawingArea, cr, width: int, height: int) -> None:  # noqa: ANN001 - cairo.Context
        cr.set_source_rgb(0.85, 0.85, 0.85)
        cr.rectangle(0, 0, width, height)
        cr.fill()

        if len(self._points) >= 2:
            elevations = [e for _, e in self._points]
            min_elevation = min(elevations)
            max_elevation = max(max(elevations), min_elevation + 1.0)

            cr.set_source_rgba(0.13, 0.44, 0.71, 0.5)
            cr.move_to(0, height)
            for distance, elevation in self._points:
                x = (distance / self._total_distance_meters) * width
                fraction = (elevation - min_elevation) / (max_elevation - min_elevation)
                cr.line_to(x, height - fraction * height)
            cr.line_to(width, height)
            cr.close_path()
            cr.fill()

        if self._progress_meters is not None:
            x = min(max(self._progress_meters, 0.0), self._total_distance_meters) / self._total_distance_meters * width
            cr.set_source_rgb(0.1, 0.1, 0.1)
            cr.set_line_width(2)
            cr.move_to(x, 0)
            cr.line_to(x, height)
            cr.stroke()
