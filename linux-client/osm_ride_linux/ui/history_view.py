"""Home screen: ride history list + overview stats. The four-tab switcher bar (History/Routes/
Workouts/Settings) lives at the MainWindow level (see main_window.py's Adw.ViewSwitcherBar), not
here.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/history/RideHistoryScreen.kt.
"""

from __future__ import annotations

import datetime
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gio", "2.0")
from gi.repository import Adw, Gio, Gtk  # noqa: E402

from ..ride.models import RideRecord  # noqa: E402
from ..util import units  # noqa: E402
from .route_thumbnail_image import build_thumbnail_widget  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402

# Same 5:3 aspect/size as RoutesView's thumbnails (see routes_view.py) - history reuses the
# route's own cached thumbnail rather than generating a separate one per ride.
_THUMBNAIL_DISPLAY_WIDTH = 160
_THUMBNAIL_DISPLAY_HEIGHT = 96


class HistoryView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.history_repository
        self._route_repo = window.app.route_repository

        self.add_top_bar(Adw.HeaderBar(title_widget=Adw.WindowTitle(title="OSM Ride")))

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        outer.set_margin_top(16)
        outer.set_margin_bottom(16)
        outer.set_margin_start(16)
        outer.set_margin_end(16)
        outer.set_valign(Gtk.Align.START)

        self._overview_group = Adw.PreferencesGroup(title="All-time")
        self._overview_row = Adw.ActionRow()
        self._overview_group.add(self._overview_row)

        self._rides_group = Adw.PreferencesGroup(title="Recent Rides")
        self._ride_rows: list[Adw.ActionRow] = []

        outer.append(self._overview_group)
        outer.append(self._rides_group)

        scroller = Gtk.ScrolledWindow()
        scroller.set_child(outer)
        self.set_content(scroller)

        # RideHistoryRepository does all its work synchronously on whatever thread calls it
        # (plain local file I/O, no BLE/network involved), and every call into it here comes
        # from a GTK button handler already running on the main thread - so no GLib.idle_add
        # marshaling is needed for this callback, unlike the BLE layer's.
        self._repo.on_rides_changed = lambda _rides: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        rides = self._repo.rides
        self._update_overview(rides)

        for row in self._ride_rows:
            self._rides_group.remove(row)
        self._ride_rows = [self._build_row(record) for record in rides]
        for row in self._ride_rows:
            self._rides_group.add(row)

    def _update_overview(self, rides: list[RideRecord]) -> None:
        total_distance = sum(r.distance_meters for r in rides)
        total_duration = sum(r.duration_seconds for r in rides)
        kcal_values = [r.estimated_kilocalories for r in rides if r.estimated_kilocalories is not None]
        total_kcal = sum(kcal_values) if kcal_values else None

        stats_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=28, halign=Gtk.Align.CENTER)
        stats_box.set_margin_top(4)
        stats_box.set_margin_bottom(4)
        for value, label in [
            (str(len(rides)), "Rides"),
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

    def _thumbnail_path(self, record: RideRecord) -> Path | None:
        if record.route_id is None:
            return None
        summary = self._route_repo.get_route_summary(record.route_id)
        if summary is None:
            return None
        return self._route_repo.thumbnail_path(summary)

    def _build_row(self, record: RideRecord) -> Adw.ActionRow:
        row = Adw.ActionRow(
            title=record.title or record.route_name,
            subtitle=self._format_date(record.completed_at_epoch_millis),
        )

        thumbnail = build_thumbnail_widget(
            self._thumbnail_path(record),
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
        dialog = Adw.AlertDialog.new("Edit Ride", None)
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
        content.append(Gtk.Label(label="Title", xalign=0.0))
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
