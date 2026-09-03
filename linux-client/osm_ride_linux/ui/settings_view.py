"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/settings/SettingsScreen.kt: a Bluetooth
Devices row (-> pairing), a Workout Library row, and the FTP field."""

from __future__ import annotations

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk  # noqa: E402


class SettingsView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        self.window = window
        app = window.app

        self.set_margin_top(12)
        self.set_margin_bottom(12)
        self.set_margin_start(16)
        self.set_margin_end(16)

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        back = Gtk.Button(label="< Back")
        back.connect("clicked", lambda _b: window.show_history())
        header.pack_start(back, False, False, 0)
        header.pack_start(Gtk.Label(label="Settings"), False, False, 0)

        pairing_row = self._make_row(
            "Bluetooth Devices",
            "Pair your smart trainer and heart rate monitor",
            lambda: window.show_pairing(),
        )
        workouts_row = self._make_row(
            "Workout Library",
            "Import .erg, .mrc, or .zwo structured workouts for ERG mode",
            lambda: window.show_workouts(),
        )

        ftp_label = Gtk.Label(label="Training", xalign=0.0)
        ftp_label.get_style_context().add_class("heading")
        ftp_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        ftp_row.pack_start(Gtk.Label(label="FTP (watts)"), False, False, 0)
        self._ftp_entry = Gtk.Entry()
        self._ftp_entry.set_input_purpose(Gtk.InputPurpose.DIGITS)
        current_ftp = app.prefs.get_ftp_watts()
        if current_ftp is not None:
            self._ftp_entry.set_text(str(current_ftp))
        self._ftp_entry.connect("changed", self._on_ftp_changed)
        ftp_row.pack_start(self._ftp_entry, False, False, 0)
        ftp_hint = Gtk.Label(
            label="Needed to convert %FTP-based .mrc/.zwo workouts to watts", xalign=0.0
        )
        ftp_hint.get_style_context().add_class("dim-label")

        self.pack_start(header, False, False, 0)
        self.pack_start(pairing_row, False, False, 0)
        self.pack_start(workouts_row, False, False, 0)
        self.pack_start(ftp_label, False, False, 0)
        self.pack_start(ftp_row, False, False, 0)
        self.pack_start(ftp_hint, False, False, 0)

    def _make_row(self, title: str, subtitle: str, on_click) -> Gtk.Widget:  # noqa: ANN001
        button = Gtk.Button()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(8)
        box.set_margin_bottom(8)
        box.set_margin_start(8)
        box.set_margin_end(8)
        title_label = Gtk.Label(label=title, xalign=0.0)
        subtitle_label = Gtk.Label(label=subtitle, xalign=0.0)
        subtitle_label.set_line_wrap(True)
        subtitle_label.get_style_context().add_class("dim-label")
        box.pack_start(title_label, False, False, 0)
        box.pack_start(subtitle_label, False, False, 0)
        button.add(box)
        button.connect("clicked", lambda _b: on_click())
        return button

    def _on_ftp_changed(self, entry: Gtk.Entry) -> None:
        digits = "".join(c for c in entry.get_text() if c.isdigit())
        if digits != entry.get_text():
            entry.set_text(digits)
            return  # setting text re-triggers "changed"; let the recursive call save it
        self.window.app.prefs.set_ftp_watts(int(digits) if digits else None)
