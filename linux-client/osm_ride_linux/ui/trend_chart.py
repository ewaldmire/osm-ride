"""Value-vs-time strip, unit-agnostic (used identically for weight and waist since callers
pre-convert to display units, e.g. lb/in) - raw daily points as a thin faded line/dots, with a
bold 7-day rolling average line and filled area on top. Daily weight/waist fluctuates from water,
sodium, timing, etc., so the average is the actionable trend, but showing the raw noise too keeps
a bad single day from misreading as a real regression.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/bodymetrics/TrendChart.kt.
"""

from __future__ import annotations

import datetime
import math

import cairo
import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402

from ..weight.trend import TrendPoint, rolling_average  # noqa: E402


def _short_date(epoch_millis: int) -> str:
    return datetime.datetime.fromtimestamp(epoch_millis / 1000).strftime("%b %-d")


class TrendChart(Gtk.DrawingArea):
    def __init__(self) -> None:
        super().__init__()
        self._points: list[TrendPoint] = []
        self.set_size_request(-1, 120)
        self.set_draw_func(self._on_draw)

    def set_points(self, points: list[TrendPoint]) -> None:
        # points may arrive in any order; the chart reads left-to-right chronologically.
        self._points = sorted(points, key=lambda p: p.epoch_millis)
        self.queue_draw()

    def _on_draw(self, _area: Gtk.DrawingArea, cr, width: int, height: int) -> None:  # noqa: ANN001 - cairo.Context
        cr.set_source_rgb(0.85, 0.85, 0.85)
        cr.rectangle(0, 0, width, height)
        cr.fill()

        if len(self._points) < 2:
            return

        averaged = rolling_average(self._points)
        all_values = [p.value for p in self._points] + [p.value for p in averaged]
        min_value = min(all_values)
        max_value = max(max(all_values), min_value + 0.1)
        min_time = self._points[0].epoch_millis
        max_time = max(self._points[-1].epoch_millis, min_time + 1)

        def x_for(epoch_millis: int) -> float:
            return (epoch_millis - min_time) / (max_time - min_time) * width

        def y_for(value: float) -> float:
            return height - (value - min_value) / (max_value - min_value) * height

        # Raw daily points - thin, faded, no fill, so they read as background noise not signal.
        cr.set_source_rgba(0.1, 0.1, 0.1, 0.35)
        cr.set_line_width(2)
        cr.move_to(x_for(self._points[0].epoch_millis), y_for(self._points[0].value))
        for point in self._points[1:]:
            cr.line_to(x_for(point.epoch_millis), y_for(point.value))
        cr.stroke()
        for point in self._points:
            cr.arc(x_for(point.epoch_millis), y_for(point.value), 3, 0, 2 * math.pi)
            cr.fill()

        # 7-day rolling average - the actual signal, drawn bold with a filled area underneath.
        cr.set_source_rgba(0.13, 0.44, 0.71, 0.5)
        cr.move_to(0, height)
        for point in averaged:
            cr.line_to(x_for(point.epoch_millis), y_for(point.value))
        cr.line_to(width, height)
        cr.close_path()
        cr.fill()

        cr.set_source_rgb(0.13, 0.44, 0.71)
        cr.set_line_width(4)
        cr.move_to(x_for(averaged[0].epoch_millis), y_for(averaged[0].value))
        for point in averaged[1:]:
            cr.line_to(x_for(point.epoch_millis), y_for(point.value))
        cr.stroke()

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
