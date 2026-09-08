"""Personal "about you" data used elsewhere in the app - FTP (for %FTP-based workouts) and
weight (its own tracking screen, see weight_view.py) - as opposed to Settings, which is just app
configuration (Bluetooth pairing).

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
        app = window.app

        self.add_top_bar(Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Profile")))

        page = Adw.PreferencesPage()

        weight_group = Adw.PreferencesGroup()
        self._weight_row = Adw.ActionRow(title="Weight", activatable=True)
        self._weight_row.add_suffix(Gtk.Image(icon_name="go-next-symbolic"))
        self._weight_row.connect("activated", lambda _r: window.show_weight())
        weight_group.add(self._weight_row)
        page.add(weight_group)

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

        # Not a persistent self._weight_repo.on_entries_changed subscription - WeightView already
        # owns that single-subscriber callback slot for its own list/chart. MainWindow.show_
        # profile() calls this directly instead, same lazy-refresh-on-navigate pattern already
        # used elsewhere (e.g. RouteCreatorView.start_edit()).
        self.refresh_weight_summary()

    def refresh_weight_summary(self) -> None:
        entries = self._weight_repo.entries
        if entries:
            latest = entries[0]
            self._weight_row.set_subtitle(
                f"{units.format_weight_lbs(latest.weight_kg)} · {self._format_date(latest.recorded_at_epoch_millis)}"
            )
        else:
            self._weight_row.set_subtitle("No weigh-ins logged yet")

    def _format_date(self, epoch_millis: int) -> str:
        dt = datetime.datetime.fromtimestamp(epoch_millis / 1000)
        return dt.strftime("%b %d, %Y")

    def _on_ftp_changed(self, entry: Adw.EntryRow) -> None:
        digits = "".join(c for c in entry.get_text() if c.isdigit())
        if digits != entry.get_text():
            entry.set_text(digits)
            return  # setting text re-triggers "changed"; let the recursive call save it
        self.window.app.prefs.set_ftp_watts(int(digits) if digits else None)
