"""Shown right after a ride finishes: lets the rider name/annotate it before landing back on
History, same as Android's "saved by default, editable after" flow (Strava/Garmin-style) rather
than a discard-or-keep prompt.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/summary/{RideSummaryScreen,
RideSummaryViewModel}.kt. Unlike Android, the ride is saved to history *before* this screen is
shown (see ride_view.py's _on_ride_finished) rather than in this view's own "on load" - there's
no ViewModel lifecycle here forcing the save to happen at first composition, so saving at the
natural point (right when the engine reports FINISHED) is simpler and equivalent.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gio", "2.0")
from gi.repository import Adw, Gio, Gtk  # noqa: E402

from ..ride.models import RideRecord  # noqa: E402
from ..util import units  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402


class RideSummaryView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.history_repository
        self._record: RideRecord | None = None

        self.add_top_bar(Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Ride Complete")))

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        outer.set_margin_top(20)
        outer.set_margin_bottom(20)
        outer.set_margin_start(24)
        outer.set_margin_end(24)
        outer.set_valign(Gtk.Align.START)

        details_group = Adw.PreferencesGroup()
        self._title_row = Adw.EntryRow(title="Ride name")
        details_group.add(self._title_row)
        self._route_label = Gtk.Label(xalign=0.0)
        self._route_label.add_css_class("dim-label")
        self._route_label.add_css_class("caption")
        self._route_label.set_margin_start(6)
        outer.append(details_group)
        outer.append(self._route_label)

        notes_label = Gtk.Label(label="Notes", xalign=0.0)
        self._notes_view = Gtk.TextView()
        self._notes_view.set_size_request(-1, 80)
        self._notes_view.set_wrap_mode(Gtk.WrapMode.WORD)
        notes_frame = Gtk.Frame()
        notes_frame.set_child(self._notes_view)
        outer.append(notes_label)
        outer.append(notes_frame)

        self._stats_group = Adw.PreferencesGroup(title="Summary")
        self._stat_rows: dict[str, Adw.ActionRow] = {}
        for key, label in [
            ("distance", "Distance"),
            ("time", "Time"),
            ("avg_speed", "Avg Speed"),
            ("calories", "Calories"),
            ("avg_power", "Avg Power"),
            ("avg_cadence", "Avg Cadence"),
            ("avg_heart_rate", "Avg Heart Rate"),
        ]:
            row = Adw.ActionRow(title=label)
            self._stat_rows[key] = row
            self._stats_group.add(row)
        outer.append(self._stats_group)

        saved_label = Gtk.Label(label="Saved to ride history.", xalign=0.0)
        saved_label.add_css_class("dim-label")
        saved_label.add_css_class("caption")
        outer.append(saved_label)

        done_button = Gtk.Button(label="Done")
        done_button.add_css_class("suggested-action")
        done_button.add_css_class("pill")
        done_button.connect("clicked", lambda _b: self._on_done())
        outer.append(done_button)

        open_file_button = Gtk.Button(label="Open GPX File Location")
        open_file_button.connect("clicked", lambda _b: self._open_gpx_location())
        outer.append(open_file_button)

        scroller = Gtk.ScrolledWindow()
        scroller.set_child(outer)
        self.set_content(scroller)

    def start(self, record: RideRecord) -> None:
        self._record = record
        self._title_row.set_text(record.title or record.route_name)
        self._route_label.set_text(f"Route: {record.route_name}")

        buf = self._notes_view.get_buffer()
        buf.set_text(record.notes)

        self._stat_rows["distance"].set_subtitle(units.format_miles(record.distance_meters))
        self._stat_rows["time"].set_subtitle(units.format_duration(record.duration_seconds))
        self._stat_rows["avg_speed"].set_subtitle(units.format_mph(record.avg_speed_mps))
        self._stat_rows["calories"].set_subtitle(units.format_kilocalories(record.estimated_kilocalories))
        self._stat_rows["avg_power"].set_subtitle(units.format_watts(record.avg_power_watts))
        self._stat_rows["avg_cadence"].set_subtitle(units.format_cadence(record.avg_cadence_rpm))
        self._stat_rows["avg_heart_rate"].set_subtitle(units.format_heart_rate(record.avg_heart_rate_bpm))

    def _on_done(self) -> None:
        if self._record is not None:
            title = self._title_row.get_text().strip() or self._record.route_name
            buf = self._notes_view.get_buffer()
            notes = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
            self._repo.update_ride(self._record.id, title, notes)
        self._record = None
        self.window.show_history()

    def _open_gpx_location(self) -> None:
        if self._record is None:
            return
        gpx_path = self._repo.gpx_file(self._record)
        Gio.AppInfo.launch_default_for_uri(f"file://{gpx_path.parent}", None)
