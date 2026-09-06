"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/settings/WorkoutsListScreen.kt: import
.erg/.mrc/.zwo workout files, list with duration/avg watts/profile chart, edit, delete.
"""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from ..ride.models import Workout  # noqa: E402
from ..ride.workout_repository import WorkoutRepositoryError  # noqa: E402
from ..util import units  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402
from .workout_profile_chart import WorkoutProfileChart  # noqa: E402


def _average_watts(workout: Workout) -> float | None:
    weighted_sum = 0.0
    total_seconds = 0.0
    for seg in workout.segments:
        if seg.start_watts is None or seg.end_watts is None:
            continue
        duration = seg.end_seconds - seg.start_seconds
        if duration <= 0:
            continue
        weighted_sum += (seg.start_watts + seg.end_watts) / 2.0 * duration
        total_seconds += duration
    return weighted_sum / total_seconds if total_seconds > 0 else None


class WorkoutsView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.workout_repository

        header = Adw.HeaderBar(
            title_widget=Adw.WindowTitle(
                title="Workout", subtitle="Import .erg, .mrc, or .zwo files for ERG mode"
            )
        )
        create_button = Gtk.Button(label="Create Workout…")
        create_button.connect("clicked", lambda _b: window.show_workout_creator_new())
        import_button = Gtk.Button(label="Import…")
        import_button.connect("clicked", self._on_import_clicked)
        header.pack_end(create_button)
        header.pack_end(import_button)
        self.add_top_bar(header)

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        outer.set_margin_top(16)
        outer.set_margin_bottom(16)
        outer.set_margin_start(16)
        outer.set_margin_end(16)
        outer.set_valign(Gtk.Align.START)

        self._empty_status = Adw.StatusPage(
            title="No workouts yet",
            description="Import a file above, or build one from scratch.",
            icon_name="system-run-symbolic",
        )
        self._workouts_group = Adw.PreferencesGroup()
        self._workout_rows: list[Gtk.Widget] = []

        outer.append(self._empty_status)
        outer.append(self._workouts_group)

        scroller = Gtk.ScrolledWindow()
        scroller.set_child(outer)
        self.set_content(scroller)

        self._repo.on_workouts_changed = lambda _w: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        workouts = self._repo.workouts
        self._empty_status.set_visible(len(workouts) == 0)
        self._workouts_group.set_visible(len(workouts) > 0)

        for row in self._workout_rows:
            self._workouts_group.remove(row)
        self._workout_rows = [self._build_row(workout) for workout in workouts]
        for row in self._workout_rows:
            self._workouts_group.add(row)

    def _build_row(self, workout: Workout) -> Gtk.Widget:
        avg_watts = _average_watts(workout)
        summary_text = units.format_duration(workout.total_duration_seconds)
        if avg_watts is not None:
            summary_text += f"  ·  avg {units.format_watts(avg_watts)}"

        row = Adw.ExpanderRow(title=workout.name, subtitle=summary_text)

        # Opens the full workout creator, which already has its own name field - covers renaming
        # too, same simplification as RoutesView's single Edit button.
        edit_button = Gtk.Button(icon_name="document-edit-symbolic", valign=Gtk.Align.CENTER)
        edit_button.set_tooltip_text("Edit Workout")
        edit_button.add_css_class("flat")
        edit_button.connect("clicked", lambda _b, w=workout: self.window.show_workout_creator_edit(w.id))
        row.add_suffix(edit_button)

        delete_button = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER)
        delete_button.set_tooltip_text("Delete")
        delete_button.add_css_class("flat")
        delete_button.connect("clicked", lambda _b, w=workout: self._delete(w))
        row.add_suffix(delete_button)

        chart = WorkoutProfileChart(workout)
        chart.set_margin_start(12)
        chart.set_margin_end(12)
        chart.set_margin_bottom(12)
        chart_row = Adw.ActionRow()
        chart_row.set_child(chart)
        row.add_row(chart_row)

        return row

    def _delete(self, workout: Workout) -> None:
        self._repo.delete_workout(workout.id)

    def _on_import_clicked(self, _button: Gtk.Button) -> None:
        dialog = Gtk.FileDialog(title="Import Workout File")
        workout_filter = Gtk.FileFilter()
        workout_filter.set_name("Workout files")
        for pattern in ("*.erg", "*.mrc", "*.zwo"):
            workout_filter.add_pattern(pattern)
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(workout_filter)
        dialog.set_filters(filters)
        dialog.open(self.window, None, self._on_import_file_chosen)

    def _on_import_file_chosen(self, dialog: Gtk.FileDialog, result: Gio.AsyncResult) -> None:
        try:
            file = dialog.open_finish(result)
        except GLib.Error:
            return  # cancelled
        path = Path(file.get_path())
        ftp_watts = self.window.app.prefs.get_ftp_watts()
        try:
            self._repo.import_workout(path, path.name, ftp_watts)
        except WorkoutRepositoryError as e:
            self._show_error(str(e))

    def _show_error(self, message: str) -> None:
        dialog = Adw.AlertDialog.new("Import Failed", message)
        dialog.add_response("ok", "OK")
        dialog.present(self.window)
