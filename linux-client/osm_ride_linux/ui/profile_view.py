"""Personal "about you" data used elsewhere in the app - FTP (for %FTP-based workouts) and
weight/waist (their own tracking screen, see body_metrics_view.py) - as opposed to Settings, which
is just app configuration (Bluetooth pairing).

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/profile/ProfileScreen.kt.
"""

from __future__ import annotations

import datetime

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from ..util import units  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402


class ProfileView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._weight_repo = window.app.weight_repository
        self._waist_repo = window.app.waist_repository
        app = window.app

        self.add_top_bar(
            Adw.HeaderBar(
                title_widget=Adw.WindowTitle(
                    title="Profile", subtitle="Personal info used to tailor your training"
                )
            )
        )

        page = Adw.PreferencesPage()

        body_metrics_group = Adw.PreferencesGroup()
        self._body_metrics_row = Adw.ActionRow(title="Body Metrics", activatable=True)
        self._body_metrics_row.add_suffix(Gtk.Image(icon_name="go-next-symbolic"))
        self._body_metrics_row.connect("activated", lambda _r: window.show_body_metrics())
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

        self.set_content(page)

        # Not a persistent on_entries_changed subscription - BodyMetricsView already owns that
        # single-subscriber callback slot on each repo for its own list/chart. MainWindow.show_
        # profile() calls this directly instead, same lazy-refresh-on-navigate pattern already
        # used elsewhere (e.g. RouteCreatorView.start_edit()).
        self.refresh_body_metrics_summary()

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

    def _format_date(self, epoch_millis: int) -> str:
        dt = datetime.datetime.fromtimestamp(epoch_millis / 1000)
        return dt.strftime("%b %d, %Y")

    def _on_ftp_changed(self, entry: Adw.EntryRow) -> None:
        digits = "".join(c for c in entry.get_text() if c.isdigit())
        if digits != entry.get_text():
            entry.set_text(digits)
            return  # setting text re-triggers "changed"; let the recursive call save it
        self.window.app.prefs.set_ftp_watts(int(digits) if digits else None)
