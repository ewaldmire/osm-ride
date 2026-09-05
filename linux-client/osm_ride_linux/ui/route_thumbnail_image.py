"""A small, fixed-size cached route thumbnail image.

Drawn via Cairo's own PNG loader (cairo.ImageSurface.create_from_png) rather than GdkPixbuf/
Gtk.Picture - both of those go through GNOME's sandboxed "glycin" image loader on modern
runtimes, which needs a working D-Bus session bus to spawn its loader subprocess and isn't
reliably available in every environment (confirmed directly: it fails outright in a headless
test sandbox with no session bus, rather than just being slower). Cairo's own PNG decoder has no
such dependency. This also sidesteps Gtk.Picture's natural size following the source image's own
pixel dimensions rather than any requested display size, however it's wrapped - a Gtk.DrawingArea
has no intrinsic content size of its own, so set_size_request() is authoritative here, the same
way it already is for WorkoutProfileChart/ElevationProfileChart.
"""

from __future__ import annotations

from pathlib import Path

import cairo
import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk  # noqa: E402


class RouteThumbnailImage(Gtk.DrawingArea):
    def __init__(self, path: Path, width: int, height: int) -> None:
        super().__init__()
        self.set_size_request(width, height)
        try:
            self._surface: cairo.ImageSurface | None = cairo.ImageSurface.create_from_png(str(path))
        except Exception:  # noqa: BLE001 - defensive against any corrupt/unreadable cached file
            self._surface = None
        self.set_draw_func(self._on_draw)

    def _on_draw(self, _area: Gtk.DrawingArea, cr: cairo.Context, width: int, height: int) -> None:
        if self._surface is None:
            return
        src_width = self._surface.get_width()
        src_height = self._surface.get_height()
        if src_width == 0 or src_height == 0:
            return
        cr.save()
        cr.scale(width / src_width, height / src_height)
        cr.set_source_surface(self._surface, 0, 0)
        cr.paint()
        cr.restore()
