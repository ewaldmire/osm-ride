"""Personal "about you" data: completed activities (any .fit-imported outdoor activity - see
activities_view.py) and body measurements/training info (weight/waist/body-fat via Body Metrics,
FTP) - as opposed to Settings, which is just app configuration (Bluetooth pairing). Tabbed the
same way as the Ride hub/Body Metrics screens, since Activities used to live under the Ride hub
before .fit imports could be non-cycling activities as well.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/profile/ProfileScreen.kt.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from ..util import units  # noqa: E402
from .activities_view import ActivitiesView  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402

_ACTIVITIES_SUBTITLE = "Completed rides, outdoor activities, and workouts"
_METRICS_SUBTITLE = "Personal info used to tailor your training"


class ProfileView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._weight_repo = window.app.weight_repository
        self._waist_repo = window.app.waist_repository
        app = window.app

        self.activities_view = ActivitiesView(window)
        self._metrics_page = self._build_metrics_page(app)

        self._stack = Gtk.Stack()
        self._stack.add_titled(self.activities_view, "activities", "Activities")
        self._stack.add_titled(self._metrics_page, "metrics", "Metrics")
        self._stack.connect("notify::visible-child-name", lambda _s, _p: self._on_tab_changed())

        switcher = Gtk.StackSwitcher()
        switcher.set_stack(self._stack)

        self._import_button = Gtk.Button(label="Import .fit…")
        self._import_button.connect("clicked", lambda _b: self.activities_view.import_activity())

        self._window_title = Adw.WindowTitle(title="Profile", subtitle=_ACTIVITIES_SUBTITLE)
        header = Adw.HeaderBar(title_widget=self._window_title)
        header.pack_end(self._import_button)
        self.add_top_bar(header)

        switcher_row = Gtk.CenterBox()
        switcher_row.set_center_widget(switcher)
        switcher_row.add_css_class("toolbar")
        self.add_top_bar(switcher_row)

        self.set_content(self._stack)
        self._on_tab_changed()

        # Not a persistent on_entries_changed subscription - BodyMetricsView already owns that
        # single-subscriber callback slot on each repo for its own list/chart. MainWindow.show_
        # profile() calls this directly instead, same lazy-refresh-on-navigate pattern already
        # used elsewhere (e.g. RouteCreatorView.start_edit()).
        self.refresh_body_metrics_summary()

    def _build_metrics_page(self, app) -> Gtk.Widget:  # noqa: ANN001 - OsmRideApplication
        page = Adw.PreferencesPage()

        body_metrics_group = Adw.PreferencesGroup()
        self._body_metrics_row = Adw.ActionRow(title="Body Metrics", activatable=True)
        self._body_metrics_row.add_suffix(Gtk.Image(icon_name="go-next-symbolic"))
        self._body_metrics_row.connect("activated", lambda _r: self.window.show_body_metrics())
        body_metrics_group.add(self._body_metrics_row)
        page.add(body_metrics_group)

        training_group = Adw.PreferencesGroup(
            title="Training",
            description="Needed to convert %FTP-based .mrc/.zwo workouts to watts",
        )
        self._ftp_row = Adw.EntryRow(title="FTP (watts)")
        current_ftp = app.prefs.get_ftp_watts()
        if current_ftp is not None:
            self._ftp_row.set_text(str(current_ftp))
        self._ftp_row.connect("changed", self._on_ftp_changed)
        training_group.add(self._ftp_row)
        page.add(training_group)

        return page

    def _on_tab_changed(self) -> None:
        is_activities = self._stack.get_visible_child_name() == "activities"
        self._import_button.set_visible(is_activities)
        self._window_title.set_subtitle(_ACTIVITIES_SUBTITLE if is_activities else _METRICS_SUBTITLE)

    def show_activities_tab(self) -> None:
        self._stack.set_visible_child_name("activities")

    def refresh_body_metrics_summary(self) -> None:
        latest_weight = self._weight_repo.entries[0] if self._weight_repo.entries else None
        latest_waist = self._waist_repo.entries[0] if self._waist_repo.entries else None
        self._body_metrics_row.set_subtitle(self._body_metrics_summary(latest_weight, latest_waist))

    def _body_metrics_summary(self, latest_weight, latest_waist) -> str:  # noqa: ANN001 - WeightEntry | None, WaistEntry | None
        if latest_weight is None and latest_waist is None:
            return "No measurements logged yet"
        parts = []
        if latest_weight is not None:
            parts.append(f"Weight {units.format_weight_lbs(latest_weight.weight_kg)}")
        if latest_waist is not None:
            parts.append(f"Waist {units.format_waist_cm(latest_waist.waist_cm)}")
        return " · ".join(parts)

    def _on_ftp_changed(self, entry: Adw.EntryRow) -> None:
        digits = "".join(c for c in entry.get_text() if c.isdigit())
        if digits != entry.get_text():
            entry.set_text(digits)
            return  # setting text re-triggers "changed"; let the recursive call save it
        self.window.app.prefs.set_ftp_watts(int(digits) if digits else None)
