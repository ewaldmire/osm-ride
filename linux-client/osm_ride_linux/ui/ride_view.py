"""The ride screen: map + live stats + Start/Pause/Finish, driving BLE samples and a 1Hz clock
tick into a RideEngine and feeding grade/ERG target back to the trainer.

Mirrors app/src/main/java/com/ewaldmire/osmride/ride/RideForegroundService.kt's drive loop and
ui/ride/RideScreen.kt's layout - but simpler, on purpose: RideForegroundService only exists
because Android destroys/recreates the owning ViewModel on navigation (e.g. backing out to fix
Bluetooth), which would otherwise stop the ride. A Gtk.Stack just hides widgets rather than
destroying them, so this view can own the RideEngine directly with no separate "service" needed -
switching away from this screen and back doesn't lose anything.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

from ..ble.models import BleConnectionState, HeartRateSample, TrainerSample  # noqa: E402
from ..ride import gpx_writer  # noqa: E402
from ..ride.engine import RideEngine  # noqa: E402
from ..ride.models import RideState, RideStats  # noqa: E402
from ..route.models import Route  # noqa: E402
from ..util import units  # noqa: E402
from .ride_map_view import RideMapView  # noqa: E402
from .workout_profile_chart import WorkoutProfileChart  # noqa: E402

_FOLLOW_ZOOM = 16.0


class RideView(Gtk.Overlay):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self.app = window.app

        self._route: Route | None = None
        self._engine: RideEngine | None = None
        self._clock_tick_source: int | None = None

        self.map_view = RideMapView()
        self.add(self.map_view)

        self._build_stats_panel()
        self._build_controls_panel()

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
                row.pack_start(label, False, False, 0)
            box.pack_start(row, False, False, 0)

        self._workout_chart = WorkoutProfileChart()
        self._workout_chart.set_visible(False)
        box.pack_start(self._workout_chart, False, False, 4)

        self._status_label = Gtk.Label(xalign=0.0)
        box.pack_start(self._status_label, False, False, 0)

        panel.add(box)
        self.add_overlay(panel)

    def _build_controls_panel(self) -> None:
        panel = Gtk.Frame()
        panel.set_valign(Gtk.Align.END)
        panel.set_halign(Gtk.Align.CENTER)
        panel.set_margin_bottom(12)

        self._button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._button_box.set_margin_top(8)
        self._button_box.set_margin_bottom(8)
        self._button_box.set_margin_start(8)
        self._button_box.set_margin_end(8)
        panel.add(self._button_box)
        self.add_overlay(panel)

        back = Gtk.Button(label="< Routes")
        back.set_valign(Gtk.Align.START)
        back.set_halign(Gtk.Align.START)
        back.set_margin_top(12)
        back.set_margin_start(12)
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

        workout = self._select_default_workout()
        self._engine.workout = workout
        self._workout_chart.set_visible(workout is not None)
        self._workout_chart.set_workout(workout)

        self.map_view.set_route(route)

        self.app.trainer_client.on_sample = self._on_trainer_sample
        self.app.heart_rate_client.on_sample = self._on_heart_rate_sample

        if self._clock_tick_source is None:
            self._clock_tick_source = GLib.timeout_add(1000, self._on_clock_tick)

        self._render_controls()
        self._on_stats_changed(self._engine.stats)

    def _select_default_workout(self):  # noqa: ANN201 - Workout | None, avoiding an import cycle
        # v1: no workout picker UI yet on this screen - ERG mode just isn't engaged unless one
        # gets attached some other way in future. Route-grade simulation (the non-ERG path)
        # still works fully without this.
        return None

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
                    stats.position.lon, stats.position.lat, _FOLLOW_ZOOM, stats.position.bearing_degrees
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

    def _render_controls(self) -> None:
        for child in list(self._button_box.get_children()):
            self._button_box.remove(child)

        state = self._engine.stats.state if self._engine is not None else RideState.IDLE
        if state == RideState.IDLE:
            start_button = Gtk.Button(label="Start Ride")
            start_button.connect("clicked", lambda _b: self._start())
            self._button_box.pack_start(start_button, False, False, 0)
        elif state == RideState.RIDING:
            pause_button = Gtk.Button(label="Pause")
            pause_button.connect("clicked", lambda _b: self._pause())
            finish_button = Gtk.Button(label="Finish")
            finish_button.connect("clicked", lambda _b: self._finish())
            self._button_box.pack_start(pause_button, False, False, 0)
            self._button_box.pack_start(finish_button, False, False, 0)
        elif state == RideState.PAUSED:
            resume_button = Gtk.Button(label="Resume")
            resume_button.connect("clicked", lambda _b: self._start())
            finish_button = Gtk.Button(label="Finish")
            finish_button.connect("clicked", lambda _b: self._finish())
            self._button_box.pack_start(resume_button, False, False, 0)
            self._button_box.pack_start(finish_button, False, False, 0)
        self._button_box.show_all()

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
