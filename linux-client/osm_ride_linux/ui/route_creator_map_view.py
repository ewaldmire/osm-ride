"""Same map_assets/map.html as RideMapView, but in "creator mode": tapping the map posts a
waypoint back to Python via a WebKit2 script message handler, and the route line shows the
BRouter-routed preview through the tapped waypoints instead of a fixed ride route.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/routecreator/RouteCreatorMapView.kt.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("WebKit2", "4.0")
from gi.repository import Gtk, WebKit2  # noqa: E402

from ..route.models import RouteWaypoint  # noqa: E402

_MAP_HTML_PATH = Path(__file__).parent / "map_assets" / "map.html"


class RouteCreatorMapView(Gtk.Box):
    def __init__(self) -> None:
        super().__init__()
        self.on_map_tapped: Callable[[float, float], None] | None = None

        self._content_manager = WebKit2.UserContentManager()
        self._content_manager.register_script_message_handler("waypointTapped")
        self._content_manager.connect("script-message-received::waypointTapped", self._on_waypoint_tapped)

        self.webview = WebKit2.WebView.new_with_user_content_manager(self._content_manager)
        self.pack_start(self.webview, True, True, 0)

        self._page_loaded = False
        self._pending_scripts: list[str] = []
        self.webview.connect("load-changed", self._on_load_changed)
        self.webview.load_uri(f"file://{_MAP_HTML_PATH}")

    def _on_load_changed(self, _webview: WebKit2.WebView, event: WebKit2.LoadEvent) -> None:
        if event != WebKit2.LoadEvent.FINISHED:
            return
        self._page_loaded = True
        self._run_js("window.enableCreatorMode();")
        pending, self._pending_scripts = self._pending_scripts, []
        for script in pending:
            self.webview.run_javascript(script, None, None, None)

    def _run_js(self, script: str) -> None:
        if not self._page_loaded:
            self._pending_scripts.append(script)
            return
        self.webview.run_javascript(script, None, None, None)

    def _on_waypoint_tapped(self, _manager: WebKit2.UserContentManager, js_result) -> None:  # noqa: ANN001
        payload = json.loads(js_result.get_js_value().to_string())
        if self.on_map_tapped:
            self.on_map_tapped(payload["lon"], payload["lat"])

    def set_waypoints(self, waypoints: list[RouteWaypoint]) -> None:
        geojson = {
            "type": "FeatureCollection",
            "features": [
                {"type": "Feature", "geometry": {"type": "Point", "coordinates": [wp.lon, wp.lat]}, "properties": {}}
                for wp in waypoints
            ],
        }
        self._run_js(f"window.setWaypoints({json.dumps(geojson)});")

    def set_preview_route(self, points: list[RouteWaypoint]) -> None:
        if len(points) < 2:
            geojson = {"type": "FeatureCollection", "features": []}
        else:
            coords = [[p.lon, p.lat] for p in points]
            geojson = {"type": "Feature", "geometry": {"type": "LineString", "coordinates": coords}, "properties": {}}
        self._run_js(f"window.setPreviewRoute({json.dumps(geojson)});")
