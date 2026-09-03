"""Tap waypoints on the map, auto-route on roads via BRouter after every tap, save.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/routecreator/{RouteCreatorScreen,
RouteCreatorViewModel}.kt.
"""

from __future__ import annotations

import asyncio

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

from ..route import brouter_client  # noqa: E402
from ..route import gpx as gpx_module  # noqa: E402
from ..route.models import RouteWaypoint  # noqa: E402
from ..route.repository import RouteRepositoryError  # noqa: E402
from ..util import units  # noqa: E402
from .route_creator_map_view import RouteCreatorMapView  # noqa: E402


class RouteCreatorView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        self.window = window
        self.app = window.app
        self._repo = window.app.route_repository

        self._existing_id: str | None = None
        self._waypoints: list[RouteWaypoint] = []
        self._raw_gpx_text: str | None = None
        self._preview_distance_meters: float | None = None
        self._preview_elevation_gain_meters: float | None = None

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        header.set_margin_top(8)
        header.set_margin_bottom(4)
        header.set_margin_start(12)
        header.set_margin_end(12)
        back = Gtk.Button(label="< Back")
        back.connect("clicked", lambda _b: self.window.show_routes())
        self._name_entry = Gtk.Entry()
        self._name_entry.set_hexpand(True)
        undo_button = Gtk.Button(label="Undo")
        undo_button.connect("clicked", lambda _b: self._undo())
        clear_button = Gtk.Button(label="Clear")
        clear_button.connect("clicked", lambda _b: self._clear())
        self._save_button = Gtk.Button(label="Save")
        self._save_button.connect("clicked", lambda _b: self._save())
        header.pack_start(back, False, False, 0)
        header.pack_start(self._name_entry, True, True, 0)
        header.pack_start(undo_button, False, False, 0)
        header.pack_start(clear_button, False, False, 0)
        header.pack_start(self._save_button, False, False, 0)

        hint = Gtk.Label(label="Tap the map to add waypoints - roads are routed automatically.")
        hint.set_margin_start(12)
        hint.set_xalign(0.0)

        self.map_view = RouteCreatorMapView()
        self.map_view.set_vexpand(True)
        self.map_view.on_map_tapped = lambda lon, lat: GLib.idle_add(self._on_map_tapped, lon, lat)

        self._summary_label = Gtk.Label()
        self._summary_label.set_margin_top(4)
        self._summary_label.set_margin_bottom(8)
        self._summary_label.set_margin_start(12)

        self.pack_start(header, False, False, 0)
        self.pack_start(hint, False, False, 0)
        self.pack_start(self.map_view, True, True, 0)
        self.pack_start(self._summary_label, False, False, 0)

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
            self._repo.save_created_route(self._existing_id, name, self._raw_gpx_text, list(self._waypoints))
        except RouteRepositoryError as e:
            self._show_error(str(e))
            return
        self.window.show_routes()

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
