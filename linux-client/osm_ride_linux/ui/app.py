"""GTK4/libadwaita application entry point - owns the repositories and BLE client as app-wide
singletons, mirroring the role OsmRideApp.kt plays for the Android app."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw  # noqa: E402

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
            self._window = MainWindow(self)
        self._window.present()

    def do_shutdown(self) -> None:
        self.async_bridge.stop()
        Adw.Application.do_shutdown(self)


def main() -> int:
    app = OsmRideApplication()
    return app.run(None)
