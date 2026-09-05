"""The ride screen: map + live stats + Start/Pause/Finish, driving BLE samples and a 1Hz clock
tick into a RideEngine and feeding grade/ERG target back to the trainer.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/RideForegroundService.kt's drive loop and
ui/ride/RideScreen.kt's layout - but simpler, on purpose: RideForegroundService only exists
because Android destroys/recreates the owning ViewModel on navigation (e.g. backing out to fix
Bluetooth), which would otherwise stop the ride. An Adw.ViewStack just hides widgets rather than
destroying them, so this view can own the RideEngine directly with no separate "service" needed -
switching away from this screen and back doesn't lose anything.

Deliberately has no header bar or bottom switcher of its own - it's the one screen the persistent
tab bar hides for (see main_window.py), so the map gets the full window while riding.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from ..ble.models import BleConnectionState, HeartRateSample, TrainerSample  # noqa: E402
from ..ride import gpx_writer  # noqa: E402
from ..ride.engine import RideEngine  # noqa: E402
from ..ride.models import RideState, RideStats, Workout  # noqa: E402
from ..route.models import Route  # noqa: E402
from ..util import units  # noqa: E402
from .ride_map_view import RideMapView  # noqa: E402
from .workout_profile_chart import WorkoutProfileChart  # noqa: E402

_DEFAULT_ZOOM = 16.0
_MIN_ZOOM = 12.0
_MAX_ZOOM = 20.0
_DEFAULT_TILT_DEGREES = 55.0
_MAX_TILT_DEGREES = 80.0


class RideView(Gtk.Overlay):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self.app = window.app

        self._route: Route | None = None
        self._engine: RideEngine | None = None
        self._clock_tick_source: int | None = None
        self._zoom_level = _DEFAULT_ZOOM
        self._tilt_degrees = _DEFAULT_TILT_DEGREES
        self._selected_workout: Workout | None = None

        self.map_view = RideMapView()
        self.set_child(self.map_view)

        self._build_drag_bar()
        self._build_stats_panel()
        self._build_map_controls_panel()
        self._build_controls_panel()

    def _build_drag_bar(self) -> None:
        # The ride screen is the one place in the app with no Adw.HeaderBar (the map wants the
        # full window while riding) - but once *any* screen uses one, GTK stops asking the
        # window manager to draw its own title bar for this window at all, so without something
        # here this screen would have no way to move the window and no window controls. A thin
        # Gtk.WindowHandle strip along the top gives both back without needing a full header.
        handle = Gtk.WindowHandle()
        handle.set_valign(Gtk.Align.START)
        handle.set_halign(Gtk.Align.FILL)

        bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL)
        bar.set_size_request(-1, 36)
        bar.add_css_class("osd")  # subtle translucent dark strip, standard style for map overlays
        spacer = Gtk.Box(hexpand=True)
        controls = Gtk.WindowControls(side=Gtk.PackType.END)
        controls.set_valign(Gtk.Align.CENTER)
        controls.set_margin_end(6)
        bar.append(spacer)
        bar.append(controls)
        handle.set_child(bar)

        self.add_overlay(handle)

    def _build_stats_panel(self) -> None:
        panel = Gtk.Frame()
        panel.set_valign(Gtk.Align.START)
        panel.set_halign(Gtk.Align.START)
        panel.set_margin_top(12)
        panel.set_margin_start(12)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        box.set_margin_start(10)
        box.set_margin_end(10)

        self._stat_labels: dict[str, Gtk.Label] = {}
        rows = [
            ["distance", "time", "speed"],
            ["cadence", "power", "heart_rate"],
            ["grade", "erg_target"],
        ]
        for row_keys in rows:
            row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=16)
            for key in row_keys:
                label = Gtk.Label(label="--")
                label.set_width_chars(12)
                self._stat_labels[key] = label
                row.append(label)
            box.append(row)

        self._workout_chart = WorkoutProfileChart()
        self._workout_chart.set_visible(False)
        self._workout_chart.set_margin_top(4)
        box.append(self._workout_chart)

        self._status_label = Gtk.Label(xalign=0.0)
        box.append(self._status_label)

        panel.set_child(box)
        self.add_overlay(panel)

    def _build_map_controls_panel(self) -> None:
        panel = Gtk.Frame()
        panel.set_valign(Gtk.Align.CENTER)
        panel.set_halign(Gtk.Align.END)
        panel.set_margin_end(12)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        box.set_margin_start(8)
        box.set_margin_end(8)

        zoom_in = Gtk.Button(icon_name="zoom-in-symbolic")
        zoom_in.connect("clicked", lambda _b: self._adjust_zoom(1.0))
        zoom_out = Gtk.Button(icon_name="zoom-out-symbolic")
        zoom_out.connect("clicked", lambda _b: self._adjust_zoom(-1.0))

        tilt_label = Gtk.Label(label="Tilt")
        self._tilt_scale = Gtk.Scale(orientation=Gtk.Orientation.VERTICAL)
        self._tilt_scale.set_range(0, _MAX_TILT_DEGREES)
        self._tilt_scale.set_value(self._tilt_degrees)
        self._tilt_scale.set_inverted(True)  # top of the slider = more tilt
        self._tilt_scale.set_draw_value(False)
        self._tilt_scale.set_size_request(-1, 100)
        self._tilt_scale.connect("value-changed", self._on_tilt_changed)

        box.append(zoom_in)
        box.append(zoom_out)
        box.append(tilt_label)
        box.append(self._tilt_scale)

        panel.set_child(box)
        self.add_overlay(panel)

    def _adjust_zoom(self, delta: float) -> None:
        self._zoom_level = min(max(self._zoom_level + delta, _MIN_ZOOM), _MAX_ZOOM)
        self.map_view.reset_manual_override()

    def _on_tilt_changed(self, scale: Gtk.Scale) -> None:
        self._tilt_degrees = scale.get_value()
        self.map_view.reset_manual_override()

    def _build_controls_panel(self) -> None:
        panel = Gtk.Frame()
        panel.set_valign(Gtk.Align.END)
        panel.set_halign(Gtk.Align.CENTER)
        panel.set_margin_bottom(12)

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        outer.set_margin_top(4)
        outer.set_margin_bottom(4)
        outer.set_margin_start(8)
        outer.set_margin_end(8)

        # Only meaningful (and only shown) before Start - the workout is fixed for the ride once
        # it begins, same as the Android ViewModel's comment on selectWorkout().
        self._workout_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._workout_label = Gtk.Label(label="Workout: None")
        self._workout_button = Gtk.Button(label="Choose")
        self._workout_button.connect("clicked", lambda _b: self._open_workout_picker())
        self._workout_row.append(self._workout_label)
        self._workout_row.append(self._workout_button)
        outer.append(self._workout_row)

        self._button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._button_box.set_margin_top(4)
        self._button_box.set_margin_bottom(4)
        outer.append(self._button_box)

        panel.set_child(outer)
        self.add_overlay(panel)

        back = Gtk.Button(icon_name="go-previous-symbolic")
        back.set_tooltip_text("Back to Routes")
        back.set_valign(Gtk.Align.START)
        back.set_halign(Gtk.Align.END)
        back.set_margin_top(12)
        back.set_margin_end(12)
        back.connect("clicked", lambda _b: self.window.show_routes())
        self.add_overlay(back)

    def load_route(self, route_id: str) -> None:
        if self._route is not None and self._route.id == route_id:
            return  # already loaded - switching screens and back shouldn't restart anything
        route = self.app.route_repository.load_route(route_id)
        if route is None:
            return

        self._route = route
        self._engine = RideEngine(route)
        self._engine.on_stats_changed = lambda stats: GLib.idle_add(self._on_stats_changed, stats)

        # Workout selection is per ride-attempt, same as the Android ViewModel - a fresh route
        # starts with none attached.
        self._set_workout(None)

        self.map_view.set_route(route)

        self.app.trainer_client.on_sample = self._on_trainer_sample
        self.app.heart_rate_client.on_sample = self._on_heart_rate_sample

        if self._clock_tick_source is None:
            self._clock_tick_source = GLib.timeout_add(1000, self._on_clock_tick)

        self._render_controls()
        self._on_stats_changed(self._engine.stats)

    def _on_trainer_sample(self, sample: TrainerSample) -> None:
        GLib.idle_add(self._apply_trainer_sample, sample)

    def _apply_trainer_sample(self, sample: TrainerSample) -> None:
        if self._engine is not None:
            self._engine.on_trainer_sample(sample)
        return False

    def _on_heart_rate_sample(self, sample: HeartRateSample) -> None:
        GLib.idle_add(self._apply_heart_rate_sample, sample)

    def _apply_heart_rate_sample(self, sample: HeartRateSample) -> None:
        if self._engine is not None:
            self._engine.on_heart_rate_sample(sample)
        return False

    def _on_clock_tick(self) -> bool:
        if self._engine is not None:
            self._engine.on_clock_tick()
        return True  # keep repeating

    def _on_stats_changed(self, stats: RideStats) -> None:
        self._stat_labels["distance"].set_text(f"Distance\n{units.format_miles(stats.distance_meters)}")
        self._stat_labels["time"].set_text(f"Time\n{units.format_duration(stats.elapsed_seconds)}")
        self._stat_labels["speed"].set_text(f"Speed\n{units.format_mph(stats.current_speed_mps)}")
        self._stat_labels["cadence"].set_text(f"Cadence\n{units.format_cadence(stats.current_cadence_rpm)}")
        self._stat_labels["power"].set_text(
            f"Power\n{units.format_watts(float(stats.current_power_watts) if stats.current_power_watts is not None else None)}"
        )
        self._stat_labels["heart_rate"].set_text(
            f"Heart Rate\n{units.format_heart_rate(stats.current_heart_rate_bpm)}"
        )
        self._stat_labels["grade"].set_text(f"Grade\n{units.format_grade(stats.current_grade_percent)}")
        self._stat_labels["erg_target"].set_text(
            f"ERG Target\n{units.format_watts(float(stats.current_target_watts) if stats.current_target_watts is not None else None)}"
        )
        if self._workout_chart.get_visible():
            self._workout_chart.set_progress_seconds(stats.elapsed_seconds)

        if stats.position is not None:
            self.map_view.update_bike_position(stats.position.lon, stats.position.lat)
            if stats.state == RideState.RIDING:
                self.map_view.follow_bike(
                    stats.position.lon,
                    stats.position.lat,
                    self._zoom_level,
                    stats.position.bearing_degrees,
                    self._tilt_degrees,
                )

        self._send_trainer_control(stats)

        connected = self.app.trainer_client.connection_state == BleConnectionState.CONNECTED
        self._status_label.set_text("" if connected else "Trainer not connected - pair it first to track distance.")

        if stats.state == RideState.FINISHED:
            self._on_ride_finished(stats)
        else:
            self._render_controls()

    def _send_trainer_control(self, stats: RideStats) -> None:
        # A workout's target power and a route's simulated grade are mutually exclusive trainer
        # control modes - ERG (workout) takes priority when both are present. set_target_power/
        # set_simulated_grade both debounce internally, so calling on every tick is cheap.
        if stats.current_target_watts is not None:
            self.app.async_bridge.submit(
                self.app.trainer_client.set_target_power(stats.current_target_watts), marshal=GLib.idle_add
            )
        elif stats.current_grade_percent is not None:
            self.app.async_bridge.submit(
                self.app.trainer_client.set_simulated_grade(stats.current_grade_percent), marshal=GLib.idle_add
            )

    def _on_ride_finished(self, stats: RideStats) -> None:
        route = self._route
        engine = self._engine
        if route is None or engine is None:
            return
        gpx_text = gpx_writer.write(route.name, engine.track_points_snapshot())
        self.app.history_repository.save_ride(route.name, stats, gpx_text)
        self._route = None
        self._engine = None
        self.window.show_history()

    def _open_workout_picker(self) -> None:
        dialog = Adw.Dialog(title="Choose Workout", content_width=360, content_height=420)
        toolbar_view = Adw.ToolbarView()
        header = Adw.HeaderBar(show_title=True)
        cancel_button = Gtk.Button(label="Cancel")
        cancel_button.connect("clicked", lambda _b: dialog.close())
        header.pack_start(cancel_button)
        toolbar_view.add_top_bar(header)

        workouts = self.app.workout_repository.workouts
        if not workouts:
            status = Adw.StatusPage(
                title="No workouts imported",
                description="Add one from the Workout Library.",
                icon_name="system-run-symbolic",
            )
            toolbar_view.set_content(status)
        else:
            list_box = Gtk.ListBox()
            list_box.add_css_class("boxed-list")
            list_box.set_margin_top(12)
            list_box.set_margin_bottom(12)
            list_box.set_margin_start(12)
            list_box.set_margin_end(12)
            none_row = Gtk.ListBoxRow()
            none_row.set_child(Gtk.Label(label="None", xalign=0.0, margin_top=8, margin_bottom=8))
            none_row.workout = None  # type: ignore[attr-defined]
            list_box.append(none_row)
            for workout in workouts:
                row = Gtk.ListBoxRow()
                row.set_child(Gtk.Label(label=workout.name, xalign=0.0, margin_top=8, margin_bottom=8))
                row.workout = workout  # type: ignore[attr-defined]
                list_box.append(row)
            list_box.connect("row-activated", self._on_workout_row_activated, dialog)
            scroller = Gtk.ScrolledWindow()
            scroller.set_child(list_box)
            toolbar_view.set_content(scroller)

        dialog.set_child(toolbar_view)
        dialog.present(self.window)

    def _on_workout_row_activated(self, _list_box: Gtk.ListBox, row: Gtk.ListBoxRow, dialog: Adw.Dialog) -> None:
        self._set_workout(getattr(row, "workout", None))
        dialog.close()

    def _set_workout(self, workout: Workout | None) -> None:
        self._selected_workout = workout
        if self._engine is not None:
            self._engine.workout = workout
        self._workout_chart.set_visible(workout is not None)
        self._workout_chart.set_workout(workout)
        self._workout_label.set_text(f"Workout: {workout.name if workout else 'None'}")
        self._workout_button.set_label("Change" if workout else "Choose")

    def _render_controls(self) -> None:
        child = self._button_box.get_first_child()
        while child is not None:
            next_child = child.get_next_sibling()
            self._button_box.remove(child)
            child = next_child

        state = self._engine.stats.state if self._engine is not None else RideState.IDLE
        self._workout_row.set_visible(state == RideState.IDLE)
        if state == RideState.IDLE:
            start_button = Gtk.Button(label="Start Ride")
            start_button.add_css_class("suggested-action")
            start_button.connect("clicked", lambda _b: self._start())
            self._button_box.append(start_button)
        elif state == RideState.RIDING:
            pause_button = Gtk.Button(label="Pause")
            pause_button.connect("clicked", lambda _b: self._pause())
            finish_button = Gtk.Button(label="Finish")
            finish_button.add_css_class("destructive-action")
            finish_button.connect("clicked", lambda _b: self._finish())
            self._button_box.append(pause_button)
            self._button_box.append(finish_button)
        elif state == RideState.PAUSED:
            resume_button = Gtk.Button(label="Resume")
            resume_button.add_css_class("suggested-action")
            resume_button.connect("clicked", lambda _b: self._start())
            finish_button = Gtk.Button(label="Finish")
            finish_button.add_css_class("destructive-action")
            finish_button.connect("clicked", lambda _b: self._finish())
            self._button_box.append(resume_button)
            self._button_box.append(finish_button)

    def _start(self) -> None:
        if self._engine is not None:
            self._engine.start()
            self._render_controls()

    def _pause(self) -> None:
        if self._engine is not None:
            self._engine.pause()
            self._render_controls()

    def _finish(self) -> None:
        if self._engine is not None:
            self._engine.finish_manually()
