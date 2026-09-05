"""The classic "workout graph" from TrainerRoad/Zwift/GoldenCheetah: one filled trapezoid per
segment (ramps get a sloped top, flats a flat one), free-ride/max-effort segments left as gaps.
An optional vertical marker shows progress through the workout.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/workout/WorkoutProfileChart.kt's Canvas
drawing, using Cairo (GTK's drawing API) instead of Compose's Canvas.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from ..ride.models import Workout  # noqa: E402


class WorkoutProfileChart(Gtk.DrawingArea):
    def __init__(self, workout: Workout | None = None) -> None:
        super().__init__()
        self._workout = workout
        self._progress_seconds: float | None = None
        self.set_size_request(-1, 56)
        self.set_draw_func(self._on_draw)

    def set_workout(self, workout: Workout | None) -> None:
        self._workout = workout
        self.queue_draw()

    def set_progress_seconds(self, seconds: float | None) -> None:
        self._progress_seconds = seconds
        self.queue_draw()

    def _on_draw(self, _area: Gtk.DrawingArea, cr, width: int, height: int) -> None:  # noqa: ANN001 - cairo.Context
        cr.set_source_rgb(0.85, 0.85, 0.85)
        cr.rectangle(0, 0, width, height)
        cr.fill()

        workout = self._workout
        if workout is None or not workout.segments:
            return

        values = [
            v
            for seg in workout.segments
            for v in (seg.start_watts, seg.end_watts)
            if v is not None
        ]
        max_watts = max(values) if values else 1
        max_watts = max(max_watts, 1)
        total_seconds = max(workout.total_duration_seconds, 1)

        cr.set_source_rgb(0.13, 0.44, 0.71)
        for seg in workout.segments:
            if seg.start_watts is None or seg.end_watts is None:
                continue
            x1 = (seg.start_seconds / total_seconds) * width
            x2 = (seg.end_seconds / total_seconds) * width
            y1 = height - (seg.start_watts / max_watts) * height
            y2 = height - (seg.end_watts / max_watts) * height
            cr.move_to(x1, height)
            cr.line_to(x1, y1)
            cr.line_to(x2, y2)
            cr.line_to(x2, height)
            cr.close_path()
            cr.fill()

        if self._progress_seconds is not None:
            x = min(max(self._progress_seconds, 0), total_seconds) / total_seconds * width
            cr.set_source_rgb(0.1, 0.1, 0.1)
            cr.set_line_width(2)
            cr.move_to(x, 0)
            cr.line_to(x, height)
            cr.stroke()
