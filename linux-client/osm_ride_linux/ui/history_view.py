"""Home screen: ride history list + overview stats, with the four-button launcher bar (Settings/
Workouts/Ride/Routes) at the bottom.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/history/RideHistoryScreen.kt.
"""

from __future__ import annotations

import datetime

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gio", "2.0")
from gi.repository import Gio, Gtk  # noqa: E402

from ..ride.models import RideRecord  # noqa: E402
from ..util import units  # noqa: E402


class HistoryView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.window = window
        self._repo = window.app.history_repository

        title_label = Gtk.Label(label="OSM Ride")
        title_label.set_margin_top(12)
        title_label.set_margin_bottom(4)
        title_label.get_style_context().add_class("title-1")

        self._overview_label = Gtk.Label()
        self._overview_label.set_margin_bottom(8)

        self._list_box = Gtk.ListBox()
        self._list_box.set_selection_mode(Gtk.SelectionMode.NONE)
        scroller = Gtk.ScrolledWindow()
        scroller.set_vexpand(True)
        scroller.add(self._list_box)

        bottom_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=0)
        bottom_bar.set_homogeneous(True)
        for label, handler in [
            ("Settings", window.show_settings),
            ("Workouts", lambda: window.show_placeholder("workouts", "Workout Library")),
            ("Ride", window.show_routes),
            ("Routes", window.show_routes),
        ]:
            button = Gtk.Button(label=label)
            button.connect("clicked", lambda _b, h=handler: h())
            bottom_bar.pack_start(button, True, True, 0)

        self.pack_start(title_label, False, False, 0)
        self.pack_start(self._overview_label, False, False, 0)
        self.pack_start(scroller, True, True, 0)
        self.pack_start(bottom_bar, False, False, 0)

        # RideHistoryRepository does all its work synchronously on whatever thread calls it
        # (plain local file I/O, no BLE/network involved), and every call into it here comes
        # from a GTK button handler already running on the main thread - so no GLib.idle_add
        # marshaling is needed for this callback, unlike the BLE layer's.
        self._repo.on_rides_changed = lambda _rides: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        for child in list(self._list_box.get_children()):
            self._list_box.remove(child)

        rides = self._repo.rides
        self._update_overview(rides)
        for record in rides:
            self._list_box.add(self._build_row(record))
        self._list_box.show_all()

    def _update_overview(self, rides: list[RideRecord]) -> None:
        total_distance = sum(r.distance_meters for r in rides)
        total_duration = sum(r.duration_seconds for r in rides)
        kcal_values = [r.estimated_kilocalories for r in rides if r.estimated_kilocalories is not None]
        total_kcal = sum(kcal_values) if kcal_values else None

        self._overview_label.set_text(
            f"All-time: {len(rides)} rides  ·  {units.format_miles(total_distance)}  ·  "
            f"{units.format_duration(total_duration)}  ·  {units.format_kilocalories(total_kcal)}"
        )

    def _build_row(self, record: RideRecord) -> Gtk.ListBoxRow:
        row = Gtk.ListBoxRow()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        box.set_margin_start(12)
        box.set_margin_end(12)

        header_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        title_label = Gtk.Label(label=record.title or record.route_name)
        title_label.set_xalign(0.0)
        header_row.pack_start(title_label, True, True, 0)
        for label, handler in [
            ("Edit", self._open_edit_dialog),
            ("Open File", self._open_gpx_location),
            ("Delete", self._delete),
        ]:
            button = Gtk.Button(label=label)
            button.connect("clicked", lambda _b, r=record, h=handler: h(r))
            header_row.pack_start(button, False, False, 0)

        date_label = Gtk.Label(label=self._format_date(record.completed_at_epoch_millis))
        date_label.set_xalign(0.0)

        stats_label = Gtk.Label(
            label=f"{units.format_miles(record.distance_meters)}  ·  "
            f"{units.format_duration(record.duration_seconds)}  ·  "
            f"{units.format_mph(record.avg_speed_mps)}  ·  "
            f"{units.format_kilocalories(record.estimated_kilocalories)}"
        )
        stats_label.set_xalign(0.0)

        box.pack_start(header_row, False, False, 0)
        box.pack_start(date_label, False, False, 0)
        box.pack_start(stats_label, False, False, 0)

        if record.notes:
            notes_label = Gtk.Label(label=record.notes)
            notes_label.set_xalign(0.0)
            notes_label.set_line_wrap(True)
            notes_label.set_max_width_chars(80)
            box.pack_start(notes_label, False, False, 0)

        row.add(box)
        return row

    def _format_date(self, epoch_millis: int) -> str:
        dt = datetime.datetime.fromtimestamp(epoch_millis / 1000)
        return dt.strftime("%b %d, %Y · %I:%M %p")

    def _open_edit_dialog(self, record: RideRecord) -> None:
        dialog = Gtk.Dialog(title="Edit Ride", transient_for=self.window, modal=True)
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Save", Gtk.ResponseType.OK)
        content = dialog.get_content_area()
        content.set_spacing(8)
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)

        content.pack_start(Gtk.Label(label="Title", xalign=0.0), False, False, 0)
        title_entry = Gtk.Entry()
        title_entry.set_text(record.title or record.route_name)
        content.pack_start(title_entry, False, False, 0)

        content.pack_start(Gtk.Label(label="Notes", xalign=0.0), False, False, 0)
        notes_view = Gtk.TextView()
        notes_view.set_size_request(-1, 100)
        notes_view.get_buffer().set_text(record.notes)
        content.pack_start(notes_view, True, True, 0)

        dialog.show_all()
        response = dialog.run()
        if response == Gtk.ResponseType.OK:
            new_title = title_entry.get_text().strip() or record.route_name
            buf = notes_view.get_buffer()
            new_notes = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
            self._repo.update_ride(record.id, new_title, new_notes)
        dialog.destroy()

    def _open_gpx_location(self, record: RideRecord) -> None:
        gpx_path = self._repo.gpx_file(record)
        Gio.AppInfo.launch_default_for_uri(f"file://{gpx_path.parent}", None)

    def _delete(self, record: RideRecord) -> None:
        self._repo.delete_ride(record.id)
