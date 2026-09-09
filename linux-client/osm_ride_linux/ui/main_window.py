"""Top-level window: an Adw.ViewStack switches between full-screen views, with libadwaita's
built-in Adw.ViewSwitcherBar as the persistent bottom tab bar - hidden only on "ride" (the map
needs the full window while riding). Only the four tab screens (Profile/Ride/Workouts/Settings)
are added with add_titled_with_icon() so they get a switcher button; sub-screens (Pairing,
Body Metrics, Ride, the two Creators) are added with add_named() so the stack can navigate to them
without the switcher ever showing a button - and without highlighting any tab - for them, mirroring
the "no tab active on sub-screens" behavior the GTK3 version's custom bar had.

"Ride" (add_titled "ride_hub") combines the old separate History/Routes tabs behind one Routes/
History switcher (see ride_hub_view.py) - both are "things you do with your rides", not two
distinct app areas. "Profile" (FTP + weight/waist, see profile_view.py) replaced History as a top-level
tab - that's personal "about you" data, not app configuration, so it doesn't belong in Settings
either.

Simpler than Android Navigation's back-stack - GTK apps don't have a system back button to wire
up, and every screen here can always get back to the hub via the switcher bar, so a flat
named-page stack is enough."""

from __future__ import annotations

from collections.abc import Callable

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw  # noqa: E402

from .body_metrics_view import BodyMetricsView
from .pairing_view import PairingView
from .profile_view import ProfileView
from .ride_hub_view import RideHubView
from .ride_summary_view import RideSummaryView
from .ride_view import RideView
from .route_creator_view import RouteCreatorView
from .settings_view import SettingsView
from .workout_creator_view import WorkoutCreatorView
from .workouts_view import WorkoutsView


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app) -> None:  # noqa: ANN001 - OsmRideApplication, avoiding an import cycle
        super().__init__(application=app, title="OSM Ride", default_width=1000, default_height=700)
        self.app = app

        self.stack = Adw.ViewStack()

        self.profile_view = ProfileView(self)
        self.stack.add_titled_with_icon(self.profile_view, "profile", "Profile", "avatar-default-symbolic")

        self.ride_hub_view = RideHubView(self)
        # "Ride" (not "Routes"/"History") - this tab is the primary way to start riding or review
        # past ones, matching Android's OsmRideBottomBar.kt.
        self.stack.add_titled_with_icon(self.ride_hub_view, "ride_hub", "Ride", "osm-ride-bike-symbolic")

        self.workouts_view = WorkoutsView(self)
        self.stack.add_titled_with_icon(self.workouts_view, "workouts", "Workout", "osm-ride-dumbbell-symbolic")

        self.settings_view = SettingsView(self)
        self.stack.add_titled_with_icon(self.settings_view, "settings", "Settings", "preferences-system-symbolic")

        self.pairing_view = PairingView(self)
        self.stack.add_named(self.pairing_view, "pairing")

        self.body_metrics_view = BodyMetricsView(self)
        self.stack.add_named(self.body_metrics_view, "body_metrics")

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
        self.stack.set_visible_child_name("ride_hub")

    def _on_page_changed(self, _stack: Adw.ViewStack, _pspec) -> None:  # noqa: ANN001
        name = self.stack.get_visible_child_name()
        self.view_switcher_bar.set_reveal(name != "ride")

    def show_profile(self) -> None:
        self.profile_view.refresh_body_metrics_summary()
        self.stack.set_visible_child_name("profile")

    def show_settings(self) -> None:
        self.stack.set_visible_child_name("settings")

    def show_pairing(self, on_back: Callable[[], None] | None = None) -> None:
        # Defaults to Settings (where Pairing is normally opened from), but callers reached from
        # elsewhere - e.g. the ride screen's "not connected" status, which needs to return to the
        # in-progress ride rather than Settings - can override where the back button goes.
        self.pairing_view.on_back = on_back or self.show_settings
        self.stack.set_visible_child_name("pairing")

    def show_body_metrics(self, on_back: Callable[[], None] | None = None) -> None:
        # Defaults to Profile (where Body Metrics is normally opened from); the ride screen's "not
        # connected"-style status isn't relevant here, but the override exists for the same
        # reason it does on show_pairing - some future caller may need somewhere else to return to.
        self.body_metrics_view.on_back = on_back or self.show_profile
        self.stack.set_visible_child_name("body_metrics")

    def show_routes(self) -> None:
        self.ride_hub_view.show_routes_tab()
        self.stack.set_visible_child_name("ride_hub")

    def show_history(self) -> None:
        self.ride_hub_view.show_history_tab()
        self.stack.set_visible_child_name("ride_hub")

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
