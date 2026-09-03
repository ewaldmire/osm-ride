"""Top-level window: a Gtk.Stack switches between full-screen views, with a persistent
BottomNavBar packed below it (hidden only on "ride" - the map needs the full window while
riding). Simpler than Android Navigation's back-stack - GTK apps don't have a system back button
to wire up, and every screen here can always get back to history via the bottom bar, so a flat
named-page stack is enough."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk  # noqa: E402

from .bottom_nav_bar import BottomNavBar
from .history_view import HistoryView
from .pairing_view import PairingView
from .ride_view import RideView
from .route_creator_view import RouteCreatorView
from .routes_view import RoutesView
from .settings_view import SettingsView
from .workout_creator_view import WorkoutCreatorView
from .workouts_view import WorkoutsView


class MainWindow(Gtk.ApplicationWindow):
    def __init__(self, app) -> None:  # noqa: ANN001 - OsmRideApplication, avoiding an import cycle
        super().__init__(application=app, title="OSM Ride")
        self.app = app
        self.set_default_size(1000, 700)

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.add(outer)

        self.stack = Gtk.Stack()
        self.bottom_nav = BottomNavBar(self)
        outer.pack_start(self.stack, True, True, 0)
        outer.pack_start(self.bottom_nav, False, False, 0)
        self.stack.connect("notify::visible-child-name", self._on_page_changed)

        self.history_view = HistoryView(self)
        self.stack.add_named(self.history_view, "history")

        self.settings_view = SettingsView(self)
        self.stack.add_named(self.settings_view, "settings")

        self.pairing_view = PairingView(self)
        self.stack.add_named(self.pairing_view, "pairing")

        self.routes_view = RoutesView(self)
        self.stack.add_named(self.routes_view, "routes")

        self.workouts_view = WorkoutsView(self)
        self.stack.add_named(self.workouts_view, "workouts")

        self.ride_view = RideView(self)
        self.stack.add_named(self.ride_view, "ride")

        self.route_creator_view = RouteCreatorView(self)
        self.stack.add_named(self.route_creator_view, "route_creator")

        self.workout_creator_view = WorkoutCreatorView(self)
        self.stack.add_named(self.workout_creator_view, "workout_creator")

        self.stack.set_visible_child_name("history")
        self.show_all()

    def _on_page_changed(self, _stack: Gtk.Stack, _pspec) -> None:  # noqa: ANN001
        name = self.stack.get_visible_child_name()
        self.bottom_nav.set_visible(name != "ride")
        self.bottom_nav.set_active_page(name)

    def show_history(self) -> None:
        self.stack.set_visible_child_name("history")

    def show_settings(self) -> None:
        self.stack.set_visible_child_name("settings")

    def show_pairing(self) -> None:
        self.stack.set_visible_child_name("pairing")

    def show_routes(self) -> None:
        self.stack.set_visible_child_name("routes")

    def show_workouts(self) -> None:
        self.stack.set_visible_child_name("workouts")

    def show_ride(self, route_id: str) -> None:
        self.ride_view.load_route(route_id)
        self.stack.set_visible_child_name("ride")

    def show_route_creator_new(self) -> None:
        self.route_creator_view.start_new()
        self.stack.set_visible_child_name("route_creator")

    def show_route_creator_edit(self, route_id: str) -> None:
        self.route_creator_view.start_edit(route_id)
        self.stack.set_visible_child_name("route_creator")

    def show_workout_creator_new(self) -> None:
        self.workout_creator_view.start_new()
        self.stack.set_visible_child_name("workout_creator")

    def show_workout_creator_edit(self, workout_id: str) -> None:
        self.workout_creator_view.start_edit(workout_id)
        self.stack.set_visible_child_name("workout_creator")

    def show_placeholder(self, name: str, title: str) -> None:
        """Temporary stand-in for screens not built yet (Routes/Workouts/Settings/Ride) - a
        real label, not a silent no-op, so it's obvious in the running app what's missing."""
        if self.stack.get_child_by_name(name) is None:
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
            box.set_valign(Gtk.Align.CENTER)
            box.set_halign(Gtk.Align.CENTER)
            label = Gtk.Label(label=f"{title}\n(not built yet)")
            label.set_justify(Gtk.Justification.CENTER)
            back = Gtk.Button(label="Back to History")
            back.connect("clicked", lambda _b: self.show_history())
            box.pack_start(label, False, False, 0)
            box.pack_start(back, False, False, 0)
            self.stack.add_named(box, name)
            # GTK3 widgets aren't visible by default when constructed, and Gtk.Stack won't
            # switch to a child that isn't - the window's one-time show_all() at construction
            # predates this child existing, so it needs its own.
            box.show_all()
        self.stack.set_visible_child_name(name)
