"""GTK4/libadwaita application entry point - owns the repositories and BLE client as app-wide
singletons, mirroring the role OsmRideApp.kt plays for the Android app."""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gtk  # noqa: E402

from ..ble.heart_rate_client import HeartRateClient
from ..ble.trainer_client import TrainerClient
from ..ride.history_repository import RideHistoryRepository
from ..ride.workout_repository import WorkoutRepository
from ..route.repository import RouteRepository
from ..util.app_prefs import AppPrefs
from ..util.async_bridge import AsyncBridge
from .main_window import MainWindow


class OsmRideApplication(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id="com.ewaldmire.OsmRideLinux")
        self.async_bridge = AsyncBridge()
        self.prefs = AppPrefs()
        self.route_repository = RouteRepository()
        self.workout_repository = WorkoutRepository()
        self.history_repository = RideHistoryRepository()
        self.trainer_client = TrainerClient()
        self.heart_rate_client = HeartRateClient()
        self._window: MainWindow | None = None

    def do_activate(self) -> None:
        if self._window is None:
            # Bundled custom icons (bike, dumbbell) - stock GTK/Adwaita has no icon for either.
            # Registered here (once a display connection exists) rather than a Flatpak manifest
            # install step, so the same source tree works both as the built app and via the
            # bare-runtime dev workflow in README.md's "Development environment" section.
            icon_theme = Gtk.IconTheme.get_for_display(Gdk.Display.get_default())
            icon_theme.add_search_path(str(Path(__file__).parent / "icons"))
            self._window = MainWindow(self)
        self._window.present()

    def do_shutdown(self) -> None:
        self.async_bridge.stop()
        Adw.Application.do_shutdown(self)


def main() -> int:
    app = OsmRideApplication()
    return app.run(None)
