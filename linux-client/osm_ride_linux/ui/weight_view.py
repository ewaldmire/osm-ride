"""Log body-weight over time, reached from History's top bar - not app configuration, so not
under Settings.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/weight/WeightScreen.kt.
"""

from __future__ import annotations

import datetime

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from ..util import units  # noqa: E402
from ..weight.models import WeightEntry  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402
from .weight_trend_chart import WeightTrendChart  # noqa: E402


class WeightView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.weight_repository

        header = Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Weight Tracking"))
        back = Gtk.Button(icon_name="go-previous-symbolic")
        back.connect("clicked", lambda _b: window.show_history())
        header.pack_start(back)
        self.add_top_bar(header)

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        outer.set_margin_top(16)
        outer.set_margin_bottom(16)
        outer.set_margin_start(16)
        outer.set_margin_end(16)
        outer.set_valign(Gtk.Align.START)

        self._selected_date = datetime.date.today()

        date_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        date_row.append(Gtk.Label(label="Date:", xalign=0.0))
        self._date_button = Gtk.MenuButton(label="Today")
        self._calendar = Gtk.Calendar()
        self._calendar.connect("day-selected", self._on_date_selected)
        date_popover = Gtk.Popover()
        date_popover.set_child(self._calendar)
        self._date_button.set_popover(date_popover)
        date_row.append(self._date_button)
        outer.append(date_row)

        add_group = Adw.PreferencesGroup()
        self._weight_entry = Adw.EntryRow(title="Weight (lb)")
        self._weight_entry.set_input_purpose(Gtk.InputPurpose.NUMBER)
        log_button = Gtk.Button(label="Log")
        log_button.add_css_class("suggested-action")
        log_button.connect("clicked", self._on_log_clicked)
        self._weight_entry.add_suffix(log_button)
        add_group.add(self._weight_entry)
        outer.append(add_group)

        self._chart = WeightTrendChart()
        outer.append(self._chart)

        self._empty_status = Adw.StatusPage(
            title="No weigh-ins logged yet",
            icon_name="preferences-system-symbolic",
        )
        self._entries_group = Adw.PreferencesGroup()
        self._entry_rows: list[Adw.ActionRow] = []
        outer.append(self._empty_status)
        outer.append(self._entries_group)

        scroller = Gtk.ScrolledWindow()
        scroller.set_child(outer)
        self.set_content(scroller)

        self._repo.on_entries_changed = lambda _entries: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        entries = self._repo.entries
        self._chart.set_entries(entries)
        self._chart.set_visible(len(entries) >= 2)
        self._empty_status.set_visible(len(entries) == 0)
        self._entries_group.set_visible(len(entries) > 0)

        for row in self._entry_rows:
            self._entries_group.remove(row)
        self._entry_rows = [self._build_row(entry) for entry in entries]
        for row in self._entry_rows:
            self._entries_group.add(row)

    def _build_row(self, entry: WeightEntry) -> Adw.ActionRow:
        row = Adw.ActionRow(
            title=units.format_weight_lbs(entry.weight_kg),
            subtitle=self._format_date(entry.recorded_at_epoch_millis),
        )
        delete_button = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER)
        delete_button.add_css_class("flat")
        delete_button.connect("clicked", lambda _b, e=entry: self._repo.delete_entry(e.id))
        row.add_suffix(delete_button)
        return row

    def _format_date(self, epoch_millis: int) -> str:
        dt = datetime.datetime.fromtimestamp(epoch_millis / 1000)
        return dt.strftime("%b %d, %Y")

    def _on_date_selected(self, calendar: Gtk.Calendar) -> None:
        gdt = calendar.get_date()
        self._selected_date = datetime.date(gdt.get_year(), gdt.get_month(), gdt.get_day_of_month())
        self._update_date_button_label()

    def _update_date_button_label(self) -> None:
        if self._selected_date == datetime.date.today():
            self._date_button.set_label("Today")
        else:
            self._date_button.set_label(self._selected_date.strftime("%b %d, %Y"))

    def _reset_selected_date(self) -> None:
        self._selected_date = datetime.date.today()
        self._calendar.select_day(GLib.DateTime.new_now_local())
        self._update_date_button_label()

    def _on_log_clicked(self, _button: Gtk.Button) -> None:
        try:
            lbs = float(self._weight_entry.get_text().strip())
        except ValueError:
            return
        if lbs <= 0:
            return
        # Local noon, not midnight - keeps the stored timestamp safely inside the selected
        # calendar day regardless of timezone, since only the date (not time-of-day) matters here.
        recorded_at = datetime.datetime.combine(self._selected_date, datetime.time(12, 0))
        self._repo.add_entry(units.lbs_to_kg(lbs), int(recorded_at.timestamp() * 1000))
        self._weight_entry.set_text("")
        self._reset_selected_date()
