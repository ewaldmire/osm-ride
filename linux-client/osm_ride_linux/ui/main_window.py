"""Top-level window: an Adw.ViewStack switches between full-screen views, with libadwaita's
built-in Adw.ViewSwitcherBar as the persistent bottom tab bar - hidden only on "ride" (the map
needs the full window while riding). Only the four tab screens (History/Routes/Workouts/
Settings) are added with add_titled_with_icon() so they get a switcher button; sub-screens
(Pairing, Ride, the two Creators) are added with add_named() so the stack can navigate to them
without the switcher ever showing a button - and without highlighting any tab - for them, mirroring
the "no tab active on sub-screens" behavior the GTK3 version's custom bar had.

Simpler than Android Navigation's back-stack - GTK apps don't have a system back button to wire
up, and every screen here can always get back to history via the switcher bar, so a flat
named-page stack is enough."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw  # noqa: E402

from .history_view import HistoryView
from .pairing_view import PairingView
from .ride_summary_view import RideSummaryView
from .ride_view import RideView
from .route_creator_view import RouteCreatorView
from .routes_view import RoutesView
from .settings_view import SettingsView
from .workout_creator_view import WorkoutCreatorView
from .workouts_view import WorkoutsView


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app) -> None:  # noqa: ANN001 - OsmRideApplication, avoiding an import cycle
        super().__init__(application=app, title="OSM Ride", default_width=1000, default_height=700)
        self.app = app

        self.stack = Adw.ViewStack()

        self.history_view = HistoryView(self)
        self.stack.add_titled_with_icon(self.history_view, "history", "History", "document-open-recent-symbolic")

        self.routes_view = RoutesView(self)
        # "Ride" (not "Routes") - this tab is the primary way to start riding, not a separate
        # browsing library; it's the same route list/create/import screen, just reframed as an
        # action, matching Android's OsmRideBottomBar.kt.
        self.stack.add_titled_with_icon(self.routes_view, "routes", "Ride", "mark-location-symbolic")

        self.workouts_view = WorkoutsView(self)
        self.stack.add_titled_with_icon(self.workouts_view, "workouts", "Workouts", "system-run-symbolic")

        self.settings_view = SettingsView(self)
        self.stack.add_titled_with_icon(self.settings_view, "settings", "Settings", "preferences-system-symbolic")

        self.pairing_view = PairingView(self)
        self.stack.add_named(self.pairing_view, "pairing")

        self.ride_view = RideView(self)
        self.stack.add_named(self.ride_view, "ride")

        self.ride_summary_view = RideSummaryView(self)
        self.stack.add_named(self.ride_summary_view, "ride_summary")

        self.route_creator_view = RouteCreatorView(self)
        self.stack.add_named(self.route_creator_view, "route_creator")

        self.workout_creator_view = WorkoutCreatorView(self)
        self.stack.add_named(self.workout_creator_view, "workout_creator")

        self.view_switcher_bar = Adw.ViewSwitcherBar()
        self.view_switcher_bar.set_stack(self.stack)
        self.view_switcher_bar.set_reveal(True)

        toolbar_view = Adw.ToolbarView()
        toolbar_view.set_content(self.stack)
        toolbar_view.add_bottom_bar(self.view_switcher_bar)
        self.set_content(toolbar_view)

        self.stack.connect("notify::visible-child-name", self._on_page_changed)
        self.stack.set_visible_child_name("history")

    def _on_page_changed(self, _stack: Adw.ViewStack, _pspec) -> None:  # noqa: ANN001
        name = self.stack.get_visible_child_name()
        self.view_switcher_bar.set_reveal(name != "ride")

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

    def show_ride_summary(self, record) -> None:  # noqa: ANN001 - RideRecord, avoiding an import cycle
        self.ride_summary_view.start(record)
        self.stack.set_visible_child_name("ride_summary")

    def show_route_creator_new(self) -> None:
        self.route_creator_view.start_new()
        self.stack.set_visible_child_name("route_creator")

    def show_route_creator_edit(self, route_id: str, show_derived_hint: bool = False) -> None:
        self.route_creator_view.start_edit(route_id, show_derived_hint=show_derived_hint)
        self.stack.set_visible_child_name("route_creator")

    def show_workout_creator_new(self) -> None:
        self.workout_creator_view.start_new()
        self.stack.set_visible_child_name("workout_creator")

    def show_workout_creator_edit(self, workout_id: str) -> None:
        self.workout_creator_view.start_edit(workout_id)
        self.stack.set_visible_child_name("workout_creator")
