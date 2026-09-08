"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/settings/SettingsScreen.kt: a Bluetooth
Devices row (-> pairing) and the FTP field. Workout Library isn't here - it's a bottom tab now."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from .toolbar_page import ToolbarPage  # noqa: E402


class SettingsView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        app = window.app

        self.add_top_bar(Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Settings")))

        page = Adw.PreferencesPage()

        devices_group = Adw.PreferencesGroup()
        pairing_row = Adw.ActionRow(
            title="Bluetooth Devices",
            subtitle="Pair your smart trainer and heart rate monitor",
            activatable=True,
        )
        pairing_row.add_suffix(Gtk.Image(icon_name="go-next-symbolic"))
        pairing_row.connect("activated", lambda _r: window.show_pairing())
        devices_group.add(pairing_row)
        page.add(devices_group)

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

    def _on_ftp_changed(self, entry: Adw.EntryRow) -> None:
        digits = "".join(c for c in entry.get_text() if c.isdigit())
        if digits != entry.get_text():
            entry.set_text(digits)
            return  # setting text re-triggers "changed"; let the recursive call save it
        self.window.app.prefs.set_ftp_watts(int(digits) if digits else None)
