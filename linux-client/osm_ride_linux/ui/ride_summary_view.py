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

# Smaller than Routes'/History's 160x96 (same 5:3 aspect) - here it sits next to the name field
# rather than owning a whole row/card to itself. A snapshot of this ride's own recorded track,
# not the route's planning thumbnail - see ride_view.py's _generate_ride_thumbnail.
_THUMBNAIL_DISPLAY_WIDTH = 120
_THUMBNAIL_DISPLAY_HEIGHT = 72

_STAT_LABELS = [
    ("distance", "Distance"),
    ("time", "Time"),
    ("avg_speed", "Avg Speed"),
    ("calories", "Calories"),
    ("avg_power", "Avg Power"),
    ("avg_cadence", "Avg Cadence"),
    ("avg_heart_rate", "Avg Heart Rate"),
]


class RideSummaryView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.history_repository
        self._record: RideRecord | None = None

        self.add_top_bar(Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Ride Complete")))

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        outer.set_margin_top(16)
        outer.set_margin_bottom(16)
        outer.set_margin_start(24)
        outer.set_margin_end(24)
        outer.set_valign(Gtk.Align.START)

        # Lets the rider see the route they just rode before they save, rather than only seeing
        # it later from History. Not ready yet at this point - generation happens in the
        # background after this screen shows (see on_thumbnail_ready) - so this starts as a
        # placeholder, same as start() does for a ride whose thumbnail generation failed.
        header_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        self._thumbnail_container = Gtk.Box()
        header_row.append(self._thumbnail_container)

        details_col = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, hexpand=True, valign=Gtk.Align.CENTER)
        details_group = Adw.PreferencesGroup()
        self._title_row = Adw.EntryRow(title="Ride name")
        details_group.add(self._title_row)
        self._route_label = Gtk.Label(xalign=0.0)
        self._route_label.add_css_class("dim-label")
        self._route_label.add_css_class("caption")
        self._route_label.set_margin_start(6)
        details_col.append(details_group)
        details_col.append(self._route_label)
        header_row.append(details_col)
        outer.append(header_row)

        notes_label = Gtk.Label(label="Notes", xalign=0.0)
        self._notes_view = Gtk.TextView()
        self._notes_view.set_size_request(-1, 60)
        self._notes_view.set_wrap_mode(Gtk.WrapMode.WORD)
        notes_frame = Gtk.Frame()
        notes_frame.set_child(self._notes_view)
        outer.append(notes_label)
        outer.append(notes_frame)

        stats_label = Gtk.Label(label="Summary", xalign=0.0)
        outer.append(stats_label)

        # A 3-column Gtk.Grid instead of one Adw.ActionRow per stat (7 full-width rows) - the
        # single biggest contributor to this screen needing a scroll before.
        stats_grid = Gtk.Grid(column_spacing=16, row_spacing=8, column_homogeneous=True)
        stats_grid.set_margin_top(10)
        stats_grid.set_margin_bottom(10)
        stats_grid.set_margin_start(10)
        stats_grid.set_margin_end(10)
        self._stat_value_labels: dict[str, Gtk.Label] = {}
        for i, (key, label) in enumerate(_STAT_LABELS):
            col, row = i % 3, i // 3
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, halign=Gtk.Align.CENTER)
            value_label = Gtk.Label()
            value_label.add_css_class("title-4")
            caption_label = Gtk.Label(label=label)
            caption_label.add_css_class("caption")
            caption_label.add_css_class("dim-label")
            box.append(value_label)
            box.append(caption_label)
            self._stat_value_labels[key] = value_label
            stats_grid.attach(box, col, row, 1, 1)
        stats_frame = Gtk.Frame()
        stats_frame.set_child(stats_grid)
        outer.append(stats_frame)

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

        self._set_thumbnail_widget(self._repo.thumbnail_path(record))

        self._stat_value_labels["distance"].set_text(units.format_miles(record.distance_meters))
        self._stat_value_labels["time"].set_text(units.format_duration(record.duration_seconds))
        self._stat_value_labels["avg_speed"].set_text(units.format_mph(record.avg_speed_mps))
        self._stat_value_labels["calories"].set_text(units.format_kilocalories(record.estimated_kilocalories))
        self._stat_value_labels["avg_power"].set_text(units.format_watts(record.avg_power_watts))
        self._stat_value_labels["avg_cadence"].set_text(units.format_cadence(record.avg_cadence_rpm))
        self._stat_value_labels["avg_heart_rate"].set_text(units.format_heart_rate(record.avg_heart_rate_bpm))

    def _set_thumbnail_widget(self, thumb_path: Path | None) -> None:
        child = self._thumbnail_container.get_first_child()
        if child is not None:
            self._thumbnail_container.remove(child)
        thumbnail = build_thumbnail_widget(
            thumb_path, _THUMBNAIL_DISPLAY_WIDTH, _THUMBNAIL_DISPLAY_HEIGHT, "document-open-recent-symbolic"
        )
        self._thumbnail_container.append(thumbnail)

    def on_thumbnail_ready(self, record_id: str, thumb_path: Path) -> None:
        # Generation finishes asynchronously, after this screen may already be showing (see
        # ride_view.py's _generate_ride_thumbnail) - only apply it if we're still displaying the
        # same ride, not whatever's been shown here since.
        if self._record is not None and self._record.id == record_id:
            self._set_thumbnail_widget(thumb_path)

    def _on_done(self) -> None:
        if self._record is not None:
            title = self._title_row.get_text().strip() or self._record.route_name
            buf = self._notes_view.get_buffer()
            notes = buf.get_text(buf.get_start_iter(), buf.get_end_iter(), False)
            self._repo.update_ride(self._record.id, title, notes)
        self._record = None
        # Not to Profile's Activities tab - matches Android's onFinished flow, which lands back
        # on route planning too now that Ride hub has no History tab of its own to return to.
        self.window.show_routes()

    def _open_gpx_location(self) -> None:
        if self._record is None:
            return
        gpx_path = self._repo.gpx_file(self._record)
        Gio.AppInfo.launch_default_for_uri(f"file://{gpx_path.parent}", None)
