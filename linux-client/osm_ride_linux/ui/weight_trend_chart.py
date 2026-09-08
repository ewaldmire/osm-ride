"""Weight-vs-time strip, same visual language as ElevationProfileChart/WorkoutProfileChart: a
filled area under the line. Weigh-ins are sparse (at most a few a week) compared to a route's
elevation samples, so a dot at each entry helps the trend read clearly even with few points.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/weight/WeightTrendChart.kt.
"""

from __future__ import annotations

import datetime

import cairo
import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from ..weight.models import WeightEntry  # noqa: E402


def _short_date(epoch_millis: int) -> str:
    return datetime.datetime.fromtimestamp(epoch_millis / 1000).strftime("%b %-d")


class WeightTrendChart(Gtk.DrawingArea):
    def __init__(self) -> None:
        super().__init__()
        self._entries: list[WeightEntry] = []
        self.set_size_request(-1, 100)
        self.set_draw_func(self._on_draw)

    def set_entries(self, entries: list[WeightEntry]) -> None:
        # entries is newest-first (matches the repository/list); the chart reads left-to-right
        # chronologically, so it needs the reverse.
        self._entries = sorted(entries, key=lambda e: e.recorded_at_epoch_millis)
        self.queue_draw()

    def _on_draw(self, _area: Gtk.DrawingArea, cr, width: int, height: int) -> None:  # noqa: ANN001 - cairo.Context
        cr.set_source_rgb(0.85, 0.85, 0.85)
        cr.rectangle(0, 0, width, height)
        cr.fill()

        if len(self._entries) < 2:
            return

        weights = [e.weight_kg for e in self._entries]
        min_weight = min(weights)
        max_weight = max(max(weights), min_weight + 0.1)
        min_time = self._entries[0].recorded_at_epoch_millis
        max_time = max(self._entries[-1].recorded_at_epoch_millis, min_time + 1)

        def x_for(epoch_millis: int) -> float:
            return (epoch_millis - min_time) / (max_time - min_time) * width

        def y_for(weight_kg: float) -> float:
            return height - (weight_kg - min_weight) / (max_weight - min_weight) * height

        cr.set_source_rgba(0.13, 0.44, 0.71, 0.5)
        cr.move_to(0, height)
        for entry in self._entries:
            cr.line_to(x_for(entry.recorded_at_epoch_millis), y_for(entry.weight_kg))
        cr.line_to(width, height)
        cr.close_path()
        cr.fill()

        cr.set_source_rgb(0.1, 0.1, 0.1)
        for entry in self._entries:
            cr.arc(x_for(entry.recorded_at_epoch_millis), y_for(entry.weight_kg), 3, 0, 2 * 3.14159265)
            cr.fill()

        # Without these, the line's x-position was scaled by real elapsed time but nothing on
        # screen actually told the viewer that - it just looked like an arbitrary shape.
        cr.select_font_face("sans-serif", cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_NORMAL)
        cr.set_font_size(12)
        cr.set_source_rgb(0.1, 0.1, 0.1)
        start_label = _short_date(min_time)
        end_label = _short_date(max_time)
        cr.move_to(4, height - 6)
        cr.show_text(start_label)
        end_extents = cr.text_extents(end_label)
        cr.move_to(width - end_extents.width - 4, height - 6)
        cr.show_text(end_label)
