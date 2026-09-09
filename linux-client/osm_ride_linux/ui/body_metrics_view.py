"""Weight and waist tracking together, behind a tab switcher (same pattern as the Ride hub, see
ride_hub_view.py) - waist is the primary "visible abs" signal per this feature's own spec, at
least as important as weight, so it gets equal billing rather than being buried as an afterthought.

Reached from Profile - not app configuration, so not under Settings.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/bodymetrics/BodyMetricsScreen.kt.
"""

from __future__ import annotations

import datetime
import time
from collections.abc import Callable
from typing import Any

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from ..util import units  # noqa: E402
from ..weight.navy_body_fat import estimate_percent  # noqa: E402
from ..weight.trend import TrendPoint, WeeklyDelta, waist_trend_points, weekly_delta, weight_trend_points  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402
from .trend_chart import TrendChart  # noqa: E402


def _to_double_or_none(text: str) -> float | None:
    try:
        return float(text) if text else None
    except ValueError:
        return None


class _WeeklyStat:
    """One column of the weekly check-in card. Mirrors BodyMetricsScreen.kt's WeeklyStat."""

    def __init__(self, label: str, unit: str) -> None:
        self._unit = unit
        self.widget = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        title = Gtk.Label(label=label, xalign=0.0)
        title.add_css_class("caption-heading")
        self._value_label = Gtk.Label(label="No data yet", xalign=0.0)
        self._change_label = Gtk.Label(xalign=0.0, visible=False)
        self._change_label.add_css_class("caption")
        self.widget.append(title)
        self.widget.append(self._value_label)
        self.widget.append(self._change_label)

    def set_delta(self, delta: WeeklyDelta | None) -> None:
        if delta is None:
            self._value_label.set_label("No data yet")
            self._change_label.set_visible(False)
            return
        self._value_label.set_label(f"{delta.current_avg:.1f} {self._unit}")
        change = delta.delta
        if change is None:
            self._change_label.set_label("Not enough history yet")
        else:
            self._change_label.set_label(f"{change:+.1f} {self._unit} this week")
        self._change_label.set_visible(True)


class _MeasurementPage:
    """One tab's worth of UI: date picker + entry field + chart + list - built once per unit
    (weight, waist) with unit-specific formatting/conversion callbacks. Mirrors how
    BodyMetricsScreen.kt's WeightTab/WaistTab composables share MeasurementEntryForm/
    MeasurementEntryRow/EmptyMeasurementState."""

    def __init__(
        self,
        repo: Any,
        unit_label: str,
        value_of: Callable[[Any], float],
        format_value: Callable[[float], str],
        to_internal: Callable[[float], float],
        empty_message: str,
        extra_widget: Gtk.Widget | None = None,
    ) -> None:
        self._repo = repo
        self._value_of = value_of
        self._format_value = format_value
        self._to_internal = to_internal
        self._selected_date = datetime.date.today()

        self.widget = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        self.widget.set_margin_top(16)
        self.widget.set_margin_bottom(16)
        self.widget.set_margin_start(16)
        self.widget.set_margin_end(16)
        self.widget.set_valign(Gtk.Align.START)

        date_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        date_row.append(Gtk.Label(label="Date:", xalign=0.0))
        self._date_button = Gtk.MenuButton(label="Today")
        self._calendar = Gtk.Calendar()
        self._calendar.connect("day-selected", self._on_date_selected)
        date_popover = Gtk.Popover()
        date_popover.set_child(self._calendar)
        self._date_button.set_popover(date_popover)
        date_row.append(self._date_button)
        self.widget.append(date_row)

        add_group = Adw.PreferencesGroup()
        self._entry_row = Adw.EntryRow(title=unit_label)
        self._entry_row.set_input_purpose(Gtk.InputPurpose.NUMBER)
        log_button = Gtk.Button(label="Log")
        log_button.add_css_class("suggested-action")
        log_button.connect("clicked", self._on_log_clicked)
        self._entry_row.add_suffix(log_button)
        add_group.add(self._entry_row)
        self.widget.append(add_group)

        if extra_widget is not None:
            self.widget.append(extra_widget)

        self._chart = TrendChart()
        self.widget.append(self._chart)

        self._empty_status = Adw.StatusPage(title=empty_message, icon_name="preferences-system-symbolic")
        self._entries_group = Adw.PreferencesGroup()
        self._entry_rows: list[Adw.ActionRow] = []
        self.widget.append(self._empty_status)
        self.widget.append(self._entries_group)

    def refresh(self, points: list[TrendPoint]) -> None:
        entries = self._repo.entries
        self._chart.set_points(points)
        self._chart.set_visible(len(points) >= 2)
        self._empty_status.set_visible(len(entries) == 0)
        self._entries_group.set_visible(len(entries) > 0)

        for row in self._entry_rows:
            self._entries_group.remove(row)
        self._entry_rows = [self._build_row(entry) for entry in entries]
        for row in self._entry_rows:
            self._entries_group.add(row)

    def _build_row(self, entry: Any) -> Adw.ActionRow:
        row = Adw.ActionRow(
            title=self._format_value(self._value_of(entry)),
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
            value = float(self._entry_row.get_text().strip())
        except ValueError:
            return
        if value <= 0:
            return
        # Local noon, not midnight - keeps the stored timestamp safely inside the selected
        # calendar day regardless of timezone, since only the date (not time-of-day) matters here.
        recorded_at = datetime.datetime.combine(self._selected_date, datetime.time(12, 0))
        self._repo.add_entry(self._to_internal(value), int(recorded_at.timestamp() * 1000))
        self._entry_row.set_text("")
        self._reset_selected_date()


class BodyMetricsView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._weight_repo = window.app.weight_repository
        self._waist_repo = window.app.waist_repository
        self._prefs = window.app.prefs

        # Overwritten per-visit by MainWindow.show_body_metrics() - defaults to Profile since
        # that's where Body Metrics is normally opened from.
        self.on_back: Callable[[], None] = window.show_profile

        header = Adw.HeaderBar(
            title_widget=Adw.WindowTitle(title="Body Metrics", subtitle="Track your weight and waist over time")
        )
        back = Gtk.Button(icon_name="go-previous-symbolic")
        back.connect("clicked", lambda _b: self.on_back())
        header.pack_start(back)
        self.add_top_bar(header)

        self._weekly_weight_stat = _WeeklyStat("Weight", "lb")
        self._weekly_waist_stat = _WeeklyStat("Waist", "cm")
        # A shrinking waist at a flat weight is still real recomposition progress, not a plateau -
        # worth calling out explicitly since the scale alone would read as nothing happening.
        self._weekly_note = Gtk.Label(xalign=0.0, wrap=True, visible=False)
        self._weekly_note.add_css_class("caption")
        self._weekly_note.add_css_class("accent")

        weekly_stats_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=24, homogeneous=True)
        weekly_stats_row.append(self._weekly_weight_stat.widget)
        weekly_stats_row.append(self._weekly_waist_stat.widget)

        weekly_title = Gtk.Label(label="This Week", xalign=0.0)
        weekly_title.add_css_class("heading")

        weekly_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        weekly_box.add_css_class("card")
        weekly_box.add_css_class("toolbar")
        weekly_box.set_margin_top(8)
        weekly_box.set_margin_bottom(8)
        weekly_box.set_margin_start(16)
        weekly_box.set_margin_end(16)
        weekly_box.append(weekly_title)
        weekly_box.append(weekly_stats_row)
        weekly_box.append(self._weekly_note)
        self.add_top_bar(weekly_box)

        self._weight_page = _MeasurementPage(
            repo=self._weight_repo,
            unit_label="Weight (lb)",
            value_of=lambda e: e.weight_kg,
            format_value=units.format_weight_lbs,
            to_internal=units.lbs_to_kg,
            empty_message="No weigh-ins logged yet",
        )
        self._waist_page = _MeasurementPage(
            repo=self._waist_repo,
            unit_label="Waist (cm)",
            value_of=lambda e: e.waist_cm,
            format_value=units.format_waist_cm,
            to_internal=lambda cm: cm,
            empty_message="No waist measurements logged yet",
            extra_widget=self._build_body_fat_card(),
        )

        self._stack = Gtk.Stack()
        self._stack.add_titled(self._weight_page.widget, "weight", "Weight")
        self._stack.add_titled(self._waist_page.widget, "waist", "Waist")

        switcher = Gtk.StackSwitcher()
        switcher.set_stack(self._stack)
        switcher_row = Gtk.CenterBox()
        switcher_row.set_center_widget(switcher)
        switcher_row.add_css_class("toolbar")
        self.add_top_bar(switcher_row)

        scroller = Gtk.ScrolledWindow()
        scroller.set_child(self._stack)
        self.set_content(scroller)

        self._weight_repo.on_entries_changed = lambda _e: self.refresh()
        self._waist_repo.on_entries_changed = lambda _e: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        weight_points = weight_trend_points(self._weight_repo.entries)
        waist_points = waist_trend_points(self._waist_repo.entries)
        self._weight_page.refresh(weight_points)
        self._waist_page.refresh(waist_points)
        self._refresh_weekly_card(weight_points, waist_points)
        self._refresh_body_fat_estimate()

    def _refresh_weekly_card(self, weight_points: list[TrendPoint], waist_points: list[TrendPoint]) -> None:
        now_millis = int(time.time() * 1000)
        weight_delta = weekly_delta(weight_points, now_millis)
        waist_delta = weekly_delta(waist_points, now_millis)
        self._weekly_weight_stat.set_delta(weight_delta)
        self._weekly_waist_stat.set_delta(waist_delta)

        # -0.6cm is roughly the old -0.25in threshold, just re-expressed in cm.
        weight_flat = weight_delta is not None and weight_delta.delta is not None and abs(weight_delta.delta) < 0.5
        waist_down = waist_delta is not None and (waist_delta.delta or 0.0) <= -0.6
        if weight_flat and waist_down:
            self._weekly_note.set_label("Waist is down even though weight is flat — still working.")
            self._weekly_note.set_visible(True)
        else:
            self._weekly_note.set_visible(False)

    def _build_body_fat_card(self) -> Gtk.Widget:
        group = Adw.PreferencesGroup(
            title="Body Fat Estimate",
            description="Navy method, from your latest waist measurement plus neck and height below.",
        )
        self._neck_row = Adw.EntryRow(title="Neck (cm)")
        neck_cm = self._prefs.get_neck_cm()
        if neck_cm is not None:
            self._neck_row.set_text(f"{neck_cm:.1f}")
        self._neck_row.connect("changed", self._on_neck_changed)
        group.add(self._neck_row)

        self._height_row = Adw.EntryRow(title="Height (cm)")
        height_cm = self._prefs.get_height_cm()
        if height_cm is not None:
            self._height_row.set_text(f"{height_cm:.1f}")
        self._height_row.connect("changed", self._on_height_changed)
        group.add(self._height_row)

        self._body_fat_label = Gtk.Label(xalign=0.0, wrap=True)
        self._body_fat_label.set_margin_top(8)

        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.append(group)
        box.append(self._body_fat_label)
        return box

    def _on_neck_changed(self, entry: Adw.EntryRow) -> None:
        text = entry.get_text()
        digits = "".join(c for c in text if c.isdigit() or c == ".")
        if digits != text:
            entry.set_text(digits)
            return  # setting text re-triggers "changed"; let the recursive call save it
        self._prefs.set_neck_cm(_to_double_or_none(digits))
        self._refresh_body_fat_estimate()

    def _on_height_changed(self, entry: Adw.EntryRow) -> None:
        text = entry.get_text()
        digits = "".join(c for c in text if c.isdigit() or c == ".")
        if digits != text:
            entry.set_text(digits)
            return
        self._prefs.set_height_cm(_to_double_or_none(digits))
        self._refresh_body_fat_estimate()

    def _refresh_body_fat_estimate(self) -> None:
        waist_entries = self._waist_repo.entries
        neck_cm = self._prefs.get_neck_cm()
        height_cm = self._prefs.get_height_cm()
        percent = None
        if waist_entries and neck_cm is not None and height_cm is not None:
            percent = estimate_percent(waist_entries[0].waist_cm, neck_cm, height_cm)
        if percent is not None:
            self._body_fat_label.set_label(f"Estimated body fat: {percent:.1f}%")
        else:
            self._body_fat_label.set_label("Enter a waist measurement, neck, and height to see an estimate.")
