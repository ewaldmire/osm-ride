"""Mirrors app/src/main/java/com/ewaldmire/osmride/ui/routes/RoutesListScreen.kt: import GPX,
build routes in-app via BRouter, list routes with distance/climb, rename/delete, tap to ride.
"""

from __future__ import annotations

from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from ..route import waypoint_simplifier  # noqa: E402
from ..route.models import RouteSummary  # noqa: E402
from ..route.repository import RouteRepositoryError  # noqa: E402
from ..util import units  # noqa: E402
from . import route_thumbnail_generator  # noqa: E402
from .route_thumbnail_image import build_thumbnail_widget  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402

# Same 5:3 aspect ratio as the generated PNG (see route_thumbnail_generator.py's
# _THUMBNAIL_WIDTH/_THUMBNAIL_HEIGHT) so the display scale is uniform, not stretched.
_THUMBNAIL_DISPLAY_WIDTH = 160
_THUMBNAIL_DISPLAY_HEIGHT = 96


class RoutesView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self._repo = window.app.route_repository

        header = Adw.HeaderBar(
            title_widget=Adw.WindowTitle(
                title="New Ride", subtitle="Create a route or import a GPX file, then tap it to start riding"
            )
        )
        create_button = Gtk.Button(label="Create Route…")
        create_button.connect("clicked", lambda _b: window.show_route_creator_new())
        import_button = Gtk.Button(label="Import GPX…")
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
            title="No routes yet",
            description="Import a GPX file or create one with the route builder.",
            icon_name="mark-location-symbolic",
        )
        self._routes_group = Adw.PreferencesGroup()
        self._route_rows: list[Adw.ActionRow] = []

        outer.append(self._empty_status)
        outer.append(self._routes_group)

        scroller = Gtk.ScrolledWindow()
        scroller.set_child(outer)
        self.set_content(scroller)

        self._repo.on_routes_changed = lambda _routes: self.refresh()
        self.refresh()

    def refresh(self) -> None:
        routes = self._repo.routes
        self._empty_status.set_visible(len(routes) == 0)
        self._routes_group.set_visible(len(routes) > 0)

        for row in self._route_rows:
            self._routes_group.remove(row)
        self._route_rows = [self._build_row(summary) for summary in routes]
        for row in self._route_rows:
            self._routes_group.add(row)

    def _build_row(self, summary: RouteSummary) -> Adw.ActionRow:
        row = Adw.ActionRow(
            title=summary.name,
            subtitle=f"{units.format_miles(summary.total_distance_meters)}  ·  "
            f"{units.format_feet(summary.elevation_gain_meters)} climb",
            activatable=True,
        )
        row.connect("activated", lambda _r, s=summary: self._select(s))

        thumb_path = self._repo.thumbnail_path(summary)
        thumbnail = build_thumbnail_widget(
            thumb_path, _THUMBNAIL_DISPLAY_WIDTH, _THUMBNAIL_DISPLAY_HEIGHT, "mark-location-symbolic"
        )
        row.add_prefix(thumbnail)

        # Every route opens the full route creator to edit - which already has its own name
        # field, covering renaming too. Plain GPX imports get a sparse waypoint list derived
        # from their track the first time they're opened this way (see _edit()).
        edit_button = Gtk.Button(icon_name="document-edit-symbolic", valign=Gtk.Align.CENTER)
        edit_button.set_tooltip_text("Edit Route")
        edit_button.add_css_class("flat")
        edit_button.connect("clicked", lambda _b, s=summary: self._edit(s))
        row.add_suffix(edit_button)

        export_button = Gtk.Button(icon_name="document-send-symbolic", valign=Gtk.Align.CENTER)
        export_button.set_tooltip_text("Export Route")
        export_button.add_css_class("flat")
        export_button.connect("clicked", lambda _b, s=summary: self._export(s))
        row.add_suffix(export_button)

        delete_button = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER)
        delete_button.set_tooltip_text("Delete")
        delete_button.add_css_class("flat")
        delete_button.connect("clicked", lambda _b, s=summary: self._delete(s))
        row.add_suffix(delete_button)

        return row

    def _select(self, summary: RouteSummary) -> None:
        self.window.show_ride(summary.id)

    def _edit(self, summary: RouteSummary) -> None:
        # Lazy backfill, not done at import time: most imports may never be edited, so only pay
        # for deriving a waypoint list the first time a route is actually opened this way.
        derived_now = False
        if summary.waypoints is None:
            route = self._repo.load_route(summary.id)
            if route is not None and len(route.points) >= 2:
                waypoints = waypoint_simplifier.derive_waypoints(route.points)
                self._repo.set_waypoints(summary.id, waypoints)
                derived_now = True
        self.window.show_route_creator_edit(summary.id, show_derived_hint=derived_now)

    def _delete(self, summary: RouteSummary) -> None:
        self._repo.delete_route(summary.id)

    def _export(self, summary: RouteSummary) -> None:
        dialog = Gtk.FileDialog(title="Export Route", initial_name=f"{summary.name}.gpx")
        dialog.save(self.window, None, lambda d, r, s=summary: self._on_export_finished(d, r, s))

    def _on_export_finished(self, dialog: Gtk.FileDialog, result: Gio.AsyncResult, summary: RouteSummary) -> None:
        try:
            file = dialog.save_finish(result)
        except GLib.Error:
            return  # cancelled
        destination = Path(file.get_path())
        source = self._repo.route_file(summary)
        try:
            destination.write_bytes(source.read_bytes())
        except OSError as e:
            self._show_error(f"Could not export route: {e}")

    def _on_import_clicked(self, _button: Gtk.Button) -> None:
        dialog = Gtk.FileDialog(title="Import GPX Route")
        gpx_filter = Gtk.FileFilter()
        gpx_filter.set_name("GPX files")
        gpx_filter.add_pattern("*.gpx")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(gpx_filter)
        dialog.set_filters(filters)
        dialog.open(self.window, None, self._on_import_file_chosen)

    def _on_import_file_chosen(self, dialog: Gtk.FileDialog, result: Gio.AsyncResult) -> None:
        try:
            file = dialog.open_finish(result)
        except GLib.Error:
            return  # cancelled
        path = Path(file.get_path())
        try:
            summary = self._repo.import_gpx(path, path.stem)
        except RouteRepositoryError as e:
            self._show_error(str(e))
            return
        self._generate_thumbnail(summary)

    def _generate_thumbnail(self, summary: RouteSummary) -> None:
        route = self._repo.load_route(summary.id)
        if route is None:
            return
        thumbnail_file_name = f"{summary.id}_thumb.png"
        destination = self._repo.directory / thumbnail_file_name

        def on_done(success: bool) -> None:
            if success:
                self._repo.set_thumbnail(summary.id, thumbnail_file_name)

        route_thumbnail_generator.generate(route, destination, on_done)

    def _show_error(self, message: str) -> None:
        dialog = Adw.AlertDialog.new("Import Failed", message)
        dialog.add_response("ok", "OK")
        dialog.present(self.window)
