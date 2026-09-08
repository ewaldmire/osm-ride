"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/settings/SettingsScreen.kt: just app
configuration (Bluetooth pairing). Personal "about you" data (FTP, weight) lives on Profile
instead - see profile_view.py."""

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

        self.add_top_bar(
            Adw.HeaderBar(
                title_widget=Adw.WindowTitle(
                    title="Settings", subtitle="Manage device connections and app configuration"
                )
            )
        )

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

        self.set_content(page)
