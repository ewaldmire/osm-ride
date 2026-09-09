"""Completed-activity feed + overview stats, plus importing an outdoor activity from a .fit file
(a bike computer/GPS watch/app recording - could be a bike ride, a run, a kayak trip, etc., see
ride/activity_type.py).

Content only, no header bar of its own - embedded inside ProfileView alongside the Metrics tab
under one shared header (with an Activities/Metrics switcher). See profile_view.py.

Unlike Android's ActivitiesScreen.kt, this only ever shows RideRecord entries - there's no
Linux-side equivalent of the fosslift strength-workout import (that's an Android-only deep link),
so "Activities" here is really "activities recorded by GPS", just no longer assumed to be cycling.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/activities/ActivitiesScreen.kt.
"""

from __future__ import annotations

import datetime
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gio", "2.0")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from ..ride import fit_parser, gpx_writer  # noqa: E402
from ..ride.activity_type import CYCLING  # noqa: E402
from ..ride.models import RecordedTrackPoint, RideRecord  # noqa: E402
from ..util import units  # noqa: E402
from . import route_thumbnail_generator  # noqa: E402
from .route_thumbnail_image import build_thumbnail_widget  # noqa: E402

# Same 5:3 aspect/size as RoutesView's thumbnails (see routes_view.py) - a snapshot of each
# activity's own recorded track, not the route's planning thumbnail (see ride_view.py's
# _generate_ride_thumbnail).
_THUMBNAIL_DISPLAY_WIDTH = 160
_THUMBNAIL_DISPLAY_HEIGHT = 96

_ACTIVITY_LABELS = {
    CYCLING: "Cycling",
    "running": "Running",
    "walking": "Walking",
    "hiking": "Hiking",
    "swimming": "Swimming",
    "kayaking": "Kayaking",
    "rowing": "Rowing",
    "other": "Activity",
}


def _activity_label(activity_type: str) -> str:
    return _ACTIVITY_LABELS.get(activity_type, "Activity")


class ActivitiesView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(hexpand=True, vexpand=True)
        self.window = window
        self._repo = window.app.history_repository

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        outer.set_margin_top(16)
        outer.set_margin_bottom(16)
        outer.set_margin_start(16)
        outer.set_margin_end(16)
        outer.set_valign(Gtk.Align.START)

        self._overview_group = Adw.PreferencesGroup(title="All-time")
        self._overview_row = Adw.ActionRow()
        self._overview_group.add(self._overview_row)

        self._activities_group = Adw.PreferencesGroup(title="Recent Activities")
        self._activity_rows: list[Adw.ActionRow] = []

        outer.append(self._overview_group)
        outer.append(self._activities_group)

        scroller = Gtk.ScrolledWindow(hexpand=True, vexpand=True)
        scroller.set_child(outer)
        self.append(scroller)

        # RideHistoryRepository does all its work synchronously on whatever thread calls it
        # (plain local file I/O, no BLE/network involved), and every call into it here comes
        # from a GTK button handler already running on the main thread - so no GLib.idle_add
        # marshaling is needed for this callback, unlike the BLE layer's.
        self._repo.on_rides_changed = lambda _rides: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        rides = self._repo.rides
        self._update_overview(rides)

        for row in self._activity_rows:
            self._activities_group.remove(row)
        self._activity_rows = [self._build_row(record) for record in rides]
        for row in self._activity_rows:
            self._activities_group.add(row)

    def _update_overview(self, rides: list[RideRecord]) -> None:
        total_distance = sum(r.distance_meters for r in rides)
        total_duration = sum(r.duration_seconds for r in rides)
        kcal_values = [r.estimated_kilocalories for r in rides if r.estimated_kilocalories is not None]
        total_kcal = sum(kcal_values) if kcal_values else None

        stats_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=28, halign=Gtk.Align.CENTER)
        stats_box.set_margin_top(4)
        stats_box.set_margin_bottom(4)
        for value, label in [
            (str(len(rides)), "Activities"),
            (units.format_miles(total_distance), "Distance"),
            (units.format_duration(total_duration), "Time"),
            (units.format_kilocalories(total_kcal), "Calories"),
        ]:
            col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            value_label = Gtk.Label(label=value)
            value_label.add_css_class("title-2")
            caption_label = Gtk.Label(label=label)
            caption_label.add_css_class("caption")
            caption_label.add_css_class("dim-label")
            col.append(value_label)
            col.append(caption_label)
            stats_box.append(col)
        self._overview_row.set_child(stats_box)

    def _build_row(self, record: RideRecord) -> Adw.ActionRow:
        row = Adw.ActionRow(
            title=record.title or record.route_name,
            subtitle=f"{_activity_label(record.activity_type)} · {self._format_date(record.completed_at_epoch_millis)}",
        )

        # This activity's own recorded track, not the route's planning thumbnail - those can
        # differ if the rider stopped early or deviated (see ride_view.py's
        # _generate_ride_thumbnail).
        thumbnail = build_thumbnail_widget(
            self._repo.thumbnail_path(record),
            _THUMBNAIL_DISPLAY_WIDTH,
            _THUMBNAIL_DISPLAY_HEIGHT,
            "document-open-recent-symbolic",
        )
        row.add_prefix(thumbnail)

        stats_label = Gtk.Label(
            label=f"{units.format_miles(record.distance_meters)}  ·  "
            f"{units.format_duration(record.duration_seconds)}  ·  "
            f"{units.format_mph(record.avg_speed_mps)}  ·  "
            f"{units.format_kilocalories(record.estimated_kilocalories)}",
            valign=Gtk.Align.CENTER,
        )
        stats_label.add_css_class("dim-label")
        stats_label.add_css_class("caption")
        row.add_suffix(stats_label)

        for icon_name, handler in [
            ("document-edit-symbolic", self._open_edit_dialog),
            ("folder-symbolic", self._open_gpx_location),
            ("user-trash-symbolic", self._delete),
        ]:
            button = Gtk.Button(icon_name=icon_name, valign=Gtk.Align.CENTER)
            button.add_css_class("flat")
            button.connect("clicked", lambda _b, r=record, h=handler: h(r))
            row.add_suffix(button)

        if record.notes:
            row.set_subtitle(f"{row.get_subtitle()}\n{record.notes}")

        return row

    def _format_date(self, epoch_millis: int) -> str:
        dt = datetime.datetime.fromtimestamp(epoch_millis / 1000)
        return dt.strftime("%b %d, %Y · %I:%M %p")

    def _open_edit_dialog(self, record: RideRecord) -> None:
        dialog = Adw.AlertDialog.new("Edit Activity", None)
        dialog.add_response("cancel", "Cancel")
        dialog.add_response("save", "Save")
        dialog.set_response_appearance("save", Adw.ResponseAppearance.SUGGESTED)
        dialog.set_default_response("save")
        dialog.set_close_response("cancel")

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        title_entry = Gtk.Entry()
        title_entry.set_text(record.title or record.route_name)
        notes_view = Gtk.TextView()
        notes_view.set_size_request(-1, 100)
        notes_view.get_buffer().set_text(record.notes)
        notes_scroller = Gtk.ScrolledWindow()
        notes_scroller.set_child(notes_view)
        content.append(Gtk.Label(label="Name", xalign=0.0))
        content.append(title_entry)
        content.append(Gtk.Label(label="Notes", xalign=0.0))
        content.append(notes_scroller)
        dialog.set_extra_child(content)

        def on_response(_dialog: Adw.AlertDialog, response: str) -> None:
            if response == "save":
                new_title = title_entry.get_text().strip() or record.route_name
                buf = notes_view.get_buffer()
                new_notes = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
                self._repo.update_ride(record.id, new_title, new_notes)

        dialog.connect("response", on_response)
        dialog.present(self.window)

    def _open_gpx_location(self, record: RideRecord) -> None:
        gpx_path = self._repo.gpx_file(record)
        Gio.AppInfo.launch_default_for_uri(f"file://{gpx_path.parent}", None)

    def _delete(self, record: RideRecord) -> None:
        self._repo.delete_ride(record.id)

    def import_activity(self) -> None:
        dialog = Gtk.FileDialog(title="Import .fit Activity")
        fit_filter = Gtk.FileFilter()
        fit_filter.set_name("FIT files")
        fit_filter.add_pattern("*.fit")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(fit_filter)
        dialog.set_filters(filters)
        dialog.open(self.window, None, self._on_import_fit_file_chosen)

    def _on_import_fit_file_chosen(self, dialog: Gtk.FileDialog, result: Gio.AsyncResult) -> None:
        try:
            file = dialog.open_finish(result)
        except GLib.Error:
            return  # cancelled
        path = Path(file.get_path())
        summary = fit_parser.parse(str(path))
        if summary is None:
            self._show_error("Could not read that .fit file")
            return

        track_points = [
            RecordedTrackPoint(
                timestamp=p.timestamp,
                lat=p.lat,
                lon=p.lon,
                elevation_meters=p.elevation_meters,
                heart_rate_bpm=p.heart_rate_bpm,
                cadence_rpm=p.cadence_rpm,
            )
            for p in summary.points
            if p.lat is not None and p.lon is not None
        ]
        if len(track_points) < 2:
            self._show_error("That .fit file has no usable GPS track")
            return

        title = path.stem or "Imported Activity"
        gpx_content = gpx_writer.write(title, track_points)
        record = self._repo.import_ride(
            title=title,
            completed_at_epoch_millis=round(summary.end_epoch_seconds * 1000),
            distance_meters=summary.distance_meters,
            duration_seconds=summary.duration_seconds,
            avg_speed_mps=summary.avg_speed_mps,
            avg_power_watts=summary.avg_power_watts,
            avg_cadence_rpm=summary.avg_cadence_rpm,
            avg_heart_rate_bpm=summary.avg_heart_rate_bpm,
            estimated_kilocalories=summary.total_calories,
            gpx_content=gpx_content,
            activity_type=summary.activity_type,
        )
        self._generate_activity_thumbnail(record, track_points)

    def _generate_activity_thumbnail(self, record: RideRecord, track_points: list[RecordedTrackPoint]) -> None:
        if len(track_points) < 2:
            return
        thumbnail_file_name = f"{record.id}_thumb.png"
        destination = self._repo.directory / thumbnail_file_name

        def on_done(success: bool) -> None:
            if success:
                self._repo.set_thumbnail(record.id, thumbnail_file_name)

        route_thumbnail_generator.generate(track_points, destination, on_done)

    def _show_error(self, message: str) -> None:
        dialog = Adw.AlertDialog.new("Import Failed", message)
        dialog.add_response("ok", "OK")
        dialog.present(self.window)
