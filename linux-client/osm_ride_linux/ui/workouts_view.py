"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/settings/WorkoutsListScreen.kt: import
.erg/.mrc/.zwo workout files, list with duration/avg watts/profile chart, rename, delete.
"""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk  # noqa: E402

from ..ride.models import Workout  # noqa: E402
from ..ride.workout_repository import WorkoutRepositoryError  # noqa: E402
from ..util import units  # noqa: E402
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


class WorkoutsView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.window = window
        self._repo = window.app.workout_repository

        self.set_margin_top(12)
        self.set_margin_bottom(12)
        self.set_margin_start(16)
        self.set_margin_end(16)

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        back = Gtk.Button(label="< Back")
        back.connect("clicked", lambda _b: window.show_history())
        import_button = Gtk.Button(label="Import Workout...")
        import_button.connect("clicked", self._on_import_clicked)
        header.pack_start(back, False, False, 0)
        header.pack_start(Gtk.Label(label="Workout Library"), False, False, 0)
        header.pack_end(import_button, False, False, 0)

        self._empty_label = Gtk.Label(label="No workouts yet. Import an .erg, .mrc, or .zwo file.")
        self._list_box = Gtk.ListBox()
        self._list_box.set_selection_mode(Gtk.SelectionMode.NONE)
        scroller = Gtk.ScrolledWindow()
        scroller.set_vexpand(True)
        scroller.add(self._list_box)

        self.pack_start(header, False, False, 0)
        self.pack_start(self._empty_label, False, False, 12)
        self.pack_start(scroller, True, True, 0)

        self._repo.on_workouts_changed = lambda _w: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        for child in list(self._list_box.get_children()):
            self._list_box.remove(child)

        workouts = self._repo.workouts
        self._empty_label.set_visible(len(workouts) == 0)
        for workout in workouts:
            self._list_box.add(self._build_row(workout))
        self._list_box.show_all()

    def _build_row(self, workout: Workout) -> Gtk.ListBoxRow:
        row = Gtk.ListBoxRow()
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        outer.set_margin_top(8)
        outer.set_margin_bottom(8)
        outer.set_margin_start(12)
        outer.set_margin_end(12)

        top_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        avg_watts = _average_watts(workout)
        summary_text = units.format_duration(workout.total_duration_seconds)
        if avg_watts is not None:
            summary_text += f"  ·  avg {units.format_watts(avg_watts)}"
        info_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        info_box.pack_start(Gtk.Label(label=workout.name, xalign=0.0), False, False, 0)
        info_box.pack_start(Gtk.Label(label=summary_text, xalign=0.0), False, False, 0)
        top_row.pack_start(info_box, True, True, 0)

        rename_button = Gtk.Button(label="Rename")
        rename_button.connect("clicked", lambda _b, w=workout: self._rename(w))
        delete_button = Gtk.Button(label="Delete")
        delete_button.connect("clicked", lambda _b, w=workout: self._delete(w))
        top_row.pack_start(rename_button, False, False, 0)
        top_row.pack_start(delete_button, False, False, 0)

        chart = WorkoutProfileChart(workout)

        outer.pack_start(top_row, False, False, 0)
        outer.pack_start(chart, False, False, 0)
        row.add(outer)
        return row

    def _rename(self, workout: Workout) -> None:
        dialog = Gtk.Dialog(title="Rename Workout", transient_for=self.window, modal=True)
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Save", Gtk.ResponseType.OK)
        content = dialog.get_content_area()
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)
        entry = Gtk.Entry()
        entry.set_text(workout.name)
        content.pack_start(entry, False, False, 0)

        dialog.show_all()
        response = dialog.run()
        if response == Gtk.ResponseType.OK:
            new_name = entry.get_text().strip() or workout.name
            self._repo.rename_workout(workout.id, new_name)
        dialog.destroy()

    def _delete(self, workout: Workout) -> None:
        self._repo.delete_workout(workout.id)

    def _on_import_clicked(self, _button: Gtk.Button) -> None:
        dialog = Gtk.FileChooserNative.new(
            "Import Workout File", self.window, Gtk.FileChooserAction.OPEN, "Import", "Cancel"
        )
        workout_filter = Gtk.FileFilter()
        workout_filter.set_name("Workout files")
        for pattern in ("*.erg", "*.mrc", "*.zwo"):
            workout_filter.add_pattern(pattern)
        dialog.add_filter(workout_filter)

        response = dialog.run()
        if response == Gtk.ResponseType.ACCEPT:
            path = Path(dialog.get_filename())
            ftp_watts = self.window.app.prefs.get_ftp_watts()
            try:
                self._repo.import_workout(path, path.name, ftp_watts)
            except WorkoutRepositoryError as e:
                self._show_error(str(e))
        dialog.destroy()

    def _show_error(self, message: str) -> None:
        dialog = Gtk.MessageDialog(
            transient_for=self.window,
            modal=True,
            message_type=Gtk.MessageType.ERROR,
            buttons=Gtk.ButtonsType.OK,
            text=message,
        )
        dialog.run()
        dialog.destroy()
