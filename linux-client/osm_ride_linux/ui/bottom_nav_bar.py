"""Persistent bottom navigation shown on every screen except "ride" (the map needs the full
window while riding). Mirrors OsmRideBottomBar.kt / the outer Scaffold's bottomBar in
OsmRideNavHost.kt on the Android side - same four tabs, same "hidden only while riding" rule.
GTK3 has no built-in "selected tab" chrome for a plain button row, so the active tab is shown
disabled instead of highlighted - reads clearly enough as "you are here" without custom CSS.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk  # noqa: E402

_TABS = [
    ("History", "history", "show_history"),
    ("Routes", "routes", "show_routes"),
    ("Workouts", "workouts", "show_workouts"),
    ("Settings", "settings", "show_settings"),
]


class BottomNavBar(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=0)
        self.set_homogeneous(True)
        self._buttons: dict[str, Gtk.Button] = {}
        for label, page_name, method_name in _TABS:
            button = Gtk.Button(label=label)
            button.connect("clicked", lambda _b, m=method_name: getattr(window, m)())
            self.pack_start(button, True, True, 0)
            self._buttons[page_name] = button

    def set_active_page(self, page_name: str | None) -> None:
        for name, button in self._buttons.items():
            button.set_sensitive(name != page_name)
