"""Block-based workout builder: add/edit/reorder/delete steady/ramp/free-ride blocks, converted
to a flat WorkoutSegment list (with running start/end seconds) on save.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/workoutcreator/{WorkoutCreatorScreen,
WorkoutCreatorViewModel}.kt. GTK has no Compose-style reactive state layer, so unlike the
Kotlin split into a Screen + ViewModel, the block list and editor dialog live directly in this
one view - same approach as RouteCreatorView.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from ..ride.models import Workout, WorkoutSegment  # noqa: E402
from ..ride.workout_repository import WorkoutRepositoryError  # noqa: E402
from ..util import units  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402
from .workout_profile_chart import WorkoutProfileChart  # noqa: E402


class BlockType(Enum):
    STEADY = "steady"
    RAMP = "ramp"
    FREE_RIDE = "free_ride"


@dataclass
class WorkoutBlockDraft:
    """One editable block in the builder - converted to a WorkoutSegment (with computed
    cumulative start/end seconds) only when previewing or saving. `watts` is used for
    BlockType.STEADY, `start_watts`/`end_watts` for BlockType.RAMP; FREE_RIDE uses neither."""

    id: str = field(default_factory=lambda: str(uuid.uuid4()))
    duration_seconds: int = 300
    type: BlockType = BlockType.STEADY
    watts: int = 150
    start_watts: int = 150
    end_watts: int = 250


def _block_summary(block: WorkoutBlockDraft) -> str:
    if block.type is BlockType.STEADY:
        return f"Steady · {block.watts}W"
    if block.type is BlockType.RAMP:
        return f"Ramp · {block.start_watts}→{block.end_watts}W"
    return "Free Ride"


def _to_segments(blocks: list[WorkoutBlockDraft]) -> list[WorkoutSegment]:
    cursor = 0.0
    segments: list[WorkoutSegment] = []
    for block in blocks:
        start = cursor
        end = start + block.duration_seconds
        cursor = end
        if block.type is BlockType.STEADY:
            segments.append(WorkoutSegment(start, end, block.watts, block.watts))
        elif block.type is BlockType.RAMP:
            segments.append(WorkoutSegment(start, end, block.start_watts, block.end_watts))
        else:
            segments.append(WorkoutSegment(start, end, None, None))
    return segments


def _segment_to_draft(seg: WorkoutSegment) -> WorkoutBlockDraft:
    duration = max(int(seg.end_seconds - seg.start_seconds), 1)
    start, end = seg.start_watts, seg.end_watts
    if start is None or end is None:
        return WorkoutBlockDraft(duration_seconds=duration, type=BlockType.FREE_RIDE)
    if start == end:
        return WorkoutBlockDraft(duration_seconds=duration, type=BlockType.STEADY, watts=start)
    return WorkoutBlockDraft(duration_seconds=duration, type=BlockType.RAMP, start_watts=start, end_watts=end)


class WorkoutCreatorView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.workout_repository

        self._existing_id: str | None = None
        self._blocks: list[WorkoutBlockDraft] = []

        header = Adw.HeaderBar()
        back = Gtk.Button(icon_name="go-previous-symbolic")
        back.connect("clicked", lambda _b: self.window.show_workouts())
        header.pack_start(back)

        self._name_entry = Gtk.Entry()
        self._name_entry.set_hexpand(True)
        self._name_entry.connect("changed", lambda _e: self._render())
        header.set_title_widget(self._name_entry)

        self._save_button = Gtk.Button(label="Save")
        self._save_button.add_css_class("suggested-action")
        self._save_button.connect("clicked", lambda _b: self._save())
        add_button = Gtk.Button(icon_name="list-add-symbolic", tooltip_text="Add Block")
        add_button.connect("clicked", lambda _b: self._open_block_editor(None))
        header.pack_end(self._save_button)
        header.pack_end(add_button)
        self.add_top_bar(header)

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)

        self._chart = WorkoutProfileChart()
        self._chart.set_visible(False)
        self._chart.set_margin_top(8)
        self._chart.set_margin_start(12)
        self._chart.set_margin_end(12)
        self._duration_label = Gtk.Label(xalign=0.0)
        self._duration_label.set_visible(False)
        self._duration_label.set_margin_start(12)
        self._duration_label.set_margin_bottom(4)

        self._empty_status = Adw.StatusPage(
            title="No blocks yet",
            description='Tap "+" to add a steady, ramp, or free-ride interval.',
            icon_name="system-run-symbolic",
            vexpand=True,
        )

        self._blocks_group = Adw.PreferencesGroup()
        self._block_rows: list[Adw.ActionRow] = []
        blocks_scroller = Gtk.ScrolledWindow()
        blocks_scroller.set_vexpand(True)
        blocks_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        blocks_box.set_margin_top(8)
        blocks_box.set_margin_bottom(16)
        blocks_box.set_margin_start(16)
        blocks_box.set_margin_end(16)
        blocks_box.append(self._blocks_group)
        blocks_scroller.set_child(blocks_box)

        content.append(self._chart)
        content.append(self._duration_label)
        content.append(self._empty_status)
        content.append(blocks_scroller)
        self.set_content(content)

    def start_new(self) -> None:
        self._existing_id = None
        self._blocks = []
        self._name_entry.set_text("New Workout")
        self._render()

    def start_edit(self, workout_id: str) -> None:
        workout = self._repo.get_workout(workout_id)
        if workout is None:
            return
        self._existing_id = workout_id
        self._blocks = [_segment_to_draft(seg) for seg in workout.segments]
        self._name_entry.set_text(workout.name)
        self._render()

    def _render(self) -> None:
        has_blocks = len(self._blocks) > 0
        self._chart.set_visible(has_blocks)
        self._duration_label.set_visible(has_blocks)
        self._empty_status.set_visible(not has_blocks)
        self._blocks_group.set_visible(has_blocks)
        self._save_button.set_sensitive(has_blocks)

        if has_blocks:
            segments = _to_segments(self._blocks)
            preview = Workout(
                id=self._existing_id or "preview",
                name=self._name_entry.get_text(),
                segments=segments,
                total_duration_seconds=max((s.end_seconds for s in segments), default=0.0),
            )
            self._chart.set_workout(preview)
            self._duration_label.set_text(units.format_duration(preview.total_duration_seconds))

        for row in self._block_rows:
            self._blocks_group.remove(row)
        self._block_rows = [self._build_row(index, block) for index, block in enumerate(self._blocks)]
        for row in self._block_rows:
            self._blocks_group.add(row)

    def _build_row(self, index: int, block: WorkoutBlockDraft) -> Adw.ActionRow:
        row = Adw.ActionRow(
            title=f"{index + 1}. {_block_summary(block)}",
            subtitle=units.format_duration(block.duration_seconds),
        )

        up_button = Gtk.Button(icon_name="go-up-symbolic", valign=Gtk.Align.CENTER)
        up_button.add_css_class("flat")
        up_button.set_sensitive(index > 0)
        up_button.connect("clicked", lambda _b, i=index: self._move_block(i, -1))
        down_button = Gtk.Button(icon_name="go-down-symbolic", valign=Gtk.Align.CENTER)
        down_button.add_css_class("flat")
        down_button.set_sensitive(index < len(self._blocks) - 1)
        down_button.connect("clicked", lambda _b, i=index: self._move_block(i, 1))
        edit_button = Gtk.Button(icon_name="document-edit-symbolic", valign=Gtk.Align.CENTER)
        edit_button.add_css_class("flat")
        edit_button.connect("clicked", lambda _b, blk=block: self._open_block_editor(blk))
        delete_button = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER)
        delete_button.add_css_class("flat")
        delete_button.connect("clicked", lambda _b, blk=block: self._remove_block(blk.id))

        for button in (up_button, down_button, edit_button, delete_button):
            row.add_suffix(button)
        return row

    def _move_block(self, index: int, delta: int) -> None:
        new_index = index + delta
        if new_index < 0 or new_index >= len(self._blocks):
            return
        self._blocks[index], self._blocks[new_index] = self._blocks[new_index], self._blocks[index]
        self._render()

    def _remove_block(self, block_id: str) -> None:
        self._blocks = [b for b in self._blocks if b.id != block_id]
        self._render()

    def _open_block_editor(self, initial: WorkoutBlockDraft | None) -> None:
        def on_done(draft: WorkoutBlockDraft | None) -> None:
            if draft is None:
                return
            if initial is None:
                self._blocks.append(draft)
            else:
                self._blocks = [draft if b.id == initial.id else b for b in self._blocks]
            self._render()

        dialog = BlockEditorDialog(initial, on_done)
        dialog.present(self.window)

    def _save(self) -> None:
        if not self._blocks:
            self._show_error("Add at least one block first")
            return
        name = self._name_entry.get_text().strip() or "New Workout"
        try:
            self._repo.save_created_workout(self._existing_id, name, _to_segments(self._blocks))
        except WorkoutRepositoryError as e:
            self._show_error(str(e))
            return
        self.window.show_workouts()

    def _show_error(self, message: str) -> None:
        dialog = Adw.AlertDialog.new("Error", message)
        dialog.add_response("ok", "OK")
        dialog.present(self.window)


class BlockEditorDialog(Adw.Dialog):
    def __init__(self, initial: WorkoutBlockDraft | None, on_done: Callable[[WorkoutBlockDraft | None], None]) -> None:
        super().__init__(title="Edit Block" if initial else "Add Block", content_width=420, content_height=460)
        self._on_done = on_done
        self._id = initial.id if initial else str(uuid.uuid4())
        self._type = initial.type if initial else BlockType.STEADY

        toolbar_view = Adw.ToolbarView()
        header = Adw.HeaderBar(show_title=True)
        cancel_button = Gtk.Button(label="Cancel")
        cancel_button.connect("clicked", lambda _b: self._finish(None))
        save_button = Gtk.Button(label="Save")
        save_button.add_css_class("suggested-action")
        save_button.connect("clicked", lambda _b: self._finish(self.build_draft()))
        header.pack_start(cancel_button)
        header.pack_end(save_button)
        toolbar_view.add_top_bar(header)

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        content.set_margin_top(16)
        content.set_margin_bottom(16)
        content.set_margin_start(16)
        content.set_margin_end(16)

        type_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=0, homogeneous=True)
        type_box.add_css_class("linked")
        steady_button = Gtk.ToggleButton(label="Steady")
        ramp_button = Gtk.ToggleButton(label="Ramp")
        ramp_button.set_group(steady_button)
        free_button = Gtk.ToggleButton(label="Free Ride")
        free_button.set_group(steady_button)
        self._type_buttons = {
            BlockType.STEADY: steady_button,
            BlockType.RAMP: ramp_button,
            BlockType.FREE_RIDE: free_button,
        }
        self._type_buttons[self._type].set_active(True)
        for button, block_type in (
            (steady_button, BlockType.STEADY),
            (ramp_button, BlockType.RAMP),
            (free_button, BlockType.FREE_RIDE),
        ):
            button.connect("toggled", self._on_type_toggled, block_type)
            type_box.append(button)

        duration_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8, homogeneous=True)
        minutes = (initial.duration_seconds if initial else 300) // 60
        seconds = (initial.duration_seconds if initial else 300) % 60
        self._minutes_entry = self._numeric_entry(str(minutes))
        self._seconds_entry = self._numeric_entry(str(seconds))
        duration_box.append(self._labeled(self._minutes_entry, "Minutes"))
        duration_box.append(self._labeled(self._seconds_entry, "Seconds"))

        self._watts_entry = self._numeric_entry(str(initial.watts if initial else 150))
        self._start_watts_entry = self._numeric_entry(str(initial.start_watts if initial else 150))
        self._end_watts_entry = self._numeric_entry(str(initial.end_watts if initial else 250))

        self._steady_row = self._labeled(self._watts_entry, "Power (W)")
        self._ramp_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8, homogeneous=True)
        self._ramp_row.append(self._labeled(self._start_watts_entry, "Start (W)"))
        self._ramp_row.append(self._labeled(self._end_watts_entry, "End (W)"))
        self._free_ride_label = Gtk.Label(
            label="No target power is sent to the trainer during this block.", xalign=0.0, wrap=True
        )

        content.append(type_box)
        content.append(duration_box)
        content.append(self._steady_row)
        content.append(self._ramp_row)
        content.append(self._free_ride_label)

        toolbar_view.set_content(content)
        self.set_child(toolbar_view)
        self._update_power_fields_visibility()

    def _finish(self, draft: WorkoutBlockDraft | None) -> None:
        self._on_done(draft)
        self.close()

    def _labeled(self, entry: Gtk.Entry, label_text: str) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        box.append(Gtk.Label(label=label_text, xalign=0.0))
        box.append(entry)
        return box

    def _numeric_entry(self, initial_text: str) -> Gtk.Entry:
        entry = Gtk.Entry()
        entry.set_input_purpose(Gtk.InputPurpose.DIGITS)
        entry.set_text(initial_text)
        entry.connect("changed", self._on_numeric_changed)
        return entry

    def _on_numeric_changed(self, entry: Gtk.Entry) -> None:
        digits = "".join(c for c in entry.get_text() if c.isdigit())
        if digits != entry.get_text():
            entry.set_text(digits)  # re-triggers "changed"; the recursive call settles it

    def _on_type_toggled(self, button: Gtk.ToggleButton, block_type: BlockType) -> None:
        if not button.get_active():
            return
        self._type = block_type
        self._update_power_fields_visibility()

    def _update_power_fields_visibility(self) -> None:
        self._steady_row.set_visible(self._type is BlockType.STEADY)
        self._ramp_row.set_visible(self._type is BlockType.RAMP)
        self._free_ride_label.set_visible(self._type is BlockType.FREE_RIDE)

    def build_draft(self) -> WorkoutBlockDraft:
        minutes = int(self._minutes_entry.get_text() or 0)
        seconds = int(self._seconds_entry.get_text() or 0)
        duration = max(minutes * 60 + seconds, 1)
        return WorkoutBlockDraft(
            id=self._id,
            duration_seconds=duration,
            type=self._type,
            watts=int(self._watts_entry.get_text() or 150),
            start_watts=int(self._start_watts_entry.get_text() or 150),
            end_watts=int(self._end_watts_entry.get_text() or 250),
        )
