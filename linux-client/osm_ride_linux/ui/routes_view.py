"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/routes/RoutesListScreen.kt: import GPX,
list routes with distance/climb, rename/delete, tap to ride.

Route creation (tap-to-build via BRouter) isn't built here yet - it needs the map, which doesn't
exist yet either. This is import + manage + select only, same scope as RideHistoryScreen's
initial pass.
"""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gtk  # noqa: E402

from ..route.models import RouteSummary  # noqa: E402
from ..route.repository import RouteRepositoryError  # noqa: E402
from ..util import units  # noqa: E402


class RoutesView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.window = window
        self._repo = window.app.route_repository

        self.set_margin_top(12)
        self.set_margin_bottom(12)
        self.set_margin_start(16)
        self.set_margin_end(16)

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        back = Gtk.Button(label="< Back")
        back.connect("clicked", lambda _b: window.show_history())
        create_button = Gtk.Button(label="Create Route...")
        create_button.connect("clicked", lambda _b: window.show_route_creator_new())
        import_button = Gtk.Button(label="Import GPX...")
        import_button.connect("clicked", self._on_import_clicked)
        header.pack_start(back, False, False, 0)
        header.pack_start(Gtk.Label(label="Routes"), False, False, 0)
        header.pack_end(import_button, False, False, 0)
        header.pack_end(create_button, False, False, 0)

        self._empty_label = Gtk.Label(label="No routes yet. Import a GPX file to get started.")
        self._list_box = Gtk.ListBox()
        self._list_box.set_selection_mode(Gtk.SelectionMode.NONE)
        scroller = Gtk.ScrolledWindow()
        scroller.set_vexpand(True)
        scroller.add(self._list_box)

        self.pack_start(header, False, False, 0)
        self.pack_start(self._empty_label, False, False, 12)
        self.pack_start(scroller, True, True, 0)

        self._repo.on_routes_changed = lambda _routes: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        for child in list(self._list_box.get_children()):
            self._list_box.remove(child)

        routes = self._repo.routes
        self._empty_label.set_visible(len(routes) == 0)
        for summary in routes:
            self._list_box.add(self._build_row(summary))
        self._list_box.show_all()

    def _build_row(self, summary: RouteSummary) -> Gtk.ListBoxRow:
        row = Gtk.ListBoxRow()
        outer = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        outer.set_margin_top(8)
        outer.set_margin_bottom(8)
        outer.set_margin_start(12)
        outer.set_margin_end(12)

        info_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        title_label = Gtk.Label(label=summary.name, xalign=0.0)
        stats_label = Gtk.Label(
            label=f"{units.format_miles(summary.total_distance_meters)}  ·  "
            f"{units.format_feet(summary.elevation_gain_meters)} climb",
            xalign=0.0,
        )
        info_box.pack_start(title_label, False, False, 0)
        info_box.pack_start(stats_label, False, False, 0)

        select_button = Gtk.Button()
        select_button.add(info_box)
        select_button.set_relief(Gtk.ReliefStyle.NONE)
        select_button.connect("clicked", lambda _b, s=summary: self._select(s))

        rename_button = Gtk.Button(label="Rename")
        rename_button.connect("clicked", lambda _b, s=summary: self._rename(s))
        delete_button = Gtk.Button(label="Delete")
        delete_button.connect("clicked", lambda _b, s=summary: self._delete(s))

        outer.pack_start(select_button, True, True, 0)
        # Only routes built in-app carry a waypoint list to re-route from - plain GPX imports
        # have nothing for the creator to reopen.
        if summary.waypoints is not None:
            edit_button = Gtk.Button(label="Edit Route")
            edit_button.connect("clicked", lambda _b, s=summary: self.window.show_route_creator_edit(s.id))
            outer.pack_start(edit_button, False, False, 0)
        outer.pack_start(rename_button, False, False, 0)
        outer.pack_start(delete_button, False, False, 0)
        row.add(outer)
        return row

    def _select(self, summary: RouteSummary) -> None:
        self.window.show_ride(summary.id)

    def _rename(self, summary: RouteSummary) -> None:
        dialog = Gtk.Dialog(title="Rename Route", transient_for=self.window, modal=True)
        dialog.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Save", Gtk.ResponseType.OK)
        content = dialog.get_content_area()
        content.set_margin_top(12)
        content.set_margin_bottom(12)
        content.set_margin_start(12)
        content.set_margin_end(12)
        entry = Gtk.Entry()
        entry.set_text(summary.name)
        content.pack_start(entry, False, False, 0)

        dialog.show_all()
        response = dialog.run()
        if response == Gtk.ResponseType.OK:
            new_name = entry.get_text().strip() or summary.name
            self._repo.rename_route(summary.id, new_name)
        dialog.destroy()

    def _delete(self, summary: RouteSummary) -> None:
        self._repo.delete_route(summary.id)

    def _on_import_clicked(self, _button: Gtk.Button) -> None:
        dialog = Gtk.FileChooserNative.new(
            "Import GPX Route", self.window, Gtk.FileChooserAction.OPEN, "Import", "Cancel"
        )
        gpx_filter = Gtk.FileFilter()
        gpx_filter.set_name("GPX files")
        gpx_filter.add_pattern("*.gpx")
        dialog.add_filter(gpx_filter)

        response = dialog.run()
        if response == Gtk.ResponseType.ACCEPT:
            path = Path(dialog.get_filename())
            try:
                self._repo.import_gpx(path, path.stem)
            except RouteRepositoryError as e:
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
