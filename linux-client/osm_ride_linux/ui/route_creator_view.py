"""Tap waypoints on the map, auto-route on roads via BRouter after every tap, save.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/routecreator/{RouteCreatorScreen,
RouteCreatorViewModel}.kt.
"""

from __future__ import annotations

import asyncio

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from ..route import brouter_client  # noqa: E402
from ..route import gpx as gpx_module  # noqa: E402
from ..route.models import RouteWaypoint  # noqa: E402
from ..route.repository import RouteRepositoryError  # noqa: E402
from ..util import units  # noqa: E402
from . import route_thumbnail_generator  # noqa: E402
from .route_creator_map_view import RouteCreatorMapView  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402


class RouteCreatorView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        self.app = window.app
        self._repo = window.app.route_repository

        self._existing_id: str | None = None
        self._waypoints: list[RouteWaypoint] = []
        self._raw_gpx_text: str | None = None
        self._preview_distance_meters: float | None = None
        self._preview_elevation_gain_meters: float | None = None

        header = Adw.HeaderBar()
        back = Gtk.Button(icon_name="go-previous-symbolic")
        back.connect("clicked", lambda _b: self.window.show_routes())
        header.pack_start(back)

        self._name_entry = Gtk.Entry()
        self._name_entry.set_hexpand(True)
        header.set_title_widget(self._name_entry)

        self._save_button = Gtk.Button(label="Save")
        self._save_button.add_css_class("suggested-action")
        self._save_button.connect("clicked", lambda _b: self._save())
        clear_button = Gtk.Button(icon_name="edit-clear-symbolic", tooltip_text="Clear")
        clear_button.connect("clicked", lambda _b: self._clear())
        undo_button = Gtk.Button(icon_name="edit-undo-symbolic", tooltip_text="Undo")
        undo_button.connect("clicked", lambda _b: self._undo())
        header.pack_end(self._save_button)
        header.pack_end(clear_button)
        header.pack_end(undo_button)
        self.add_top_bar(header)

        content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)

        hint = Gtk.Label(label="Tap the map to add waypoints - roads are routed automatically.")
        hint.set_margin_top(4)
        hint.set_margin_start(12)
        hint.set_xalign(0.0)

        self.map_view = RouteCreatorMapView()
        self.map_view.set_vexpand(True)
        self.map_view.on_map_tapped = lambda lon, lat: GLib.idle_add(self._on_map_tapped, lon, lat)

        self._summary_label = Gtk.Label()
        self._summary_label.set_margin_top(4)
        self._summary_label.set_margin_bottom(8)
        self._summary_label.set_margin_start(12)
        self._summary_label.set_xalign(0.0)

        content.append(hint)
        content.append(self.map_view)
        content.append(self._summary_label)
        self.set_content(content)

    def start_new(self) -> None:
        self._existing_id = None
        self._waypoints = []
        self._name_entry.set_text("New Route")
        self._clear_preview()
        self.map_view.set_waypoints([])

    def start_edit(self, route_id: str) -> None:
        summary = self._repo.get_route_summary(route_id)
        if summary is None or summary.waypoints is None:
            return  # not a route this editor can open - no waypoint list to work from
        self._existing_id = route_id
        self._waypoints = list(summary.waypoints)
        self._name_entry.set_text(summary.name)
        self._clear_preview()
        self.map_view.set_waypoints(self._waypoints)
        if len(self._waypoints) >= 2:
            self._route_current_waypoints()

    def _on_map_tapped(self, lon: float, lat: float) -> bool:
        self._waypoints.append(RouteWaypoint(lat=lat, lon=lon))
        self.map_view.set_waypoints(self._waypoints)
        self._route_current_waypoints()
        return False

    def _undo(self) -> None:
        if not self._waypoints:
            return
        self._waypoints.pop()
        self.map_view.set_waypoints(self._waypoints)
        self._route_current_waypoints()

    def _clear(self) -> None:
        self._waypoints = []
        self.map_view.set_waypoints([])
        self._clear_preview()

    def _clear_preview(self) -> None:
        self._raw_gpx_text = None
        self._preview_distance_meters = None
        self._preview_elevation_gain_meters = None
        self.map_view.set_preview_route([])
        self._update_summary()
        self._save_button.set_sensitive(False)

    def _route_current_waypoints(self) -> None:
        if len(self._waypoints) < 2:
            self._clear_preview()
            return

        waypoints = list(self._waypoints)

        async def _route_async() -> str:
            # brouter_client.route() is a plain blocking function (urllib), not asyncio - run it
            # off the event loop thread's own thread pool so it doesn't stall other async work.
            return await asyncio.to_thread(brouter_client.route, waypoints)

        self.app.async_bridge.submit(
            _route_async(), on_done=self._on_routed, on_error=self._on_route_error, marshal=GLib.idle_add
        )

    def _on_routed(self, gpx_text: str) -> None:
        self._raw_gpx_text = gpx_text
        parsed = gpx_module.parse(gpx_text)
        preview_points = [RouteWaypoint(lat=p.lat, lon=p.lon) for p in parsed.points]
        self._preview_distance_meters = parsed.total_distance_meters
        self._preview_elevation_gain_meters = parsed.elevation_gain_meters
        self.map_view.set_preview_route(preview_points)
        self._update_summary()
        self._save_button.set_sensitive(True)

    def _on_route_error(self, error: BaseException) -> None:
        self._raw_gpx_text = None
        self._preview_distance_meters = None
        self._preview_elevation_gain_meters = None
        self._save_button.set_sensitive(False)
        self._show_error(f"Could not route those waypoints: {error}")

    def _update_summary(self) -> None:
        if self._preview_distance_meters is None:
            self._summary_label.set_text("")
            return
        self._summary_label.set_text(
            f"{units.format_miles(self._preview_distance_meters)}  ·  "
            f"{units.format_feet(self._preview_elevation_gain_meters or 0.0)} climb"
        )

    def _save(self) -> None:
        if self._raw_gpx_text is None:
            return
        name = self._name_entry.get_text().strip() or "New Route"
        try:
            summary = self._repo.save_created_route(
                self._existing_id, name, self._raw_gpx_text, list(self._waypoints)
            )
        except RouteRepositoryError as e:
            self._show_error(str(e))
            return
        self._generate_thumbnail(summary)
        self.window.show_routes()

    def _generate_thumbnail(self, summary) -> None:  # noqa: ANN001 - RouteSummary
        # Re-editing a route's waypoints changes its shape, so its cached thumbnail (if any) is
        # now stale - regenerate unconditionally rather than only for brand-new routes.
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
        dialog = Adw.AlertDialog.new("Error", message)
        dialog.add_response("ok", "OK")
        dialog.present(self.window)
