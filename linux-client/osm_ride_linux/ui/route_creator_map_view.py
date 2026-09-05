"""Same map_assets/map.html as RideMapView, but in "creator mode": tapping the map posts a
waypoint back to Python via a WebKit script message handler, and the route line shows the
BRouter-routed preview through the tapped waypoints instead of a fixed ride route.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/routecreator/RouteCreatorMapView.kt. See
ride_map_view.py's docstring for why readiness is signalled by the page itself via a "pageReady"
script message rather than WebKit's "load-changed" FINISHED signal.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("WebKit", "6.0")
from gi.repository import Gtk, WebKit  # noqa: E402

from ..route.models import RouteWaypoint  # noqa: E402

_MAP_HTML_PATH = Path(__file__).parent / "map_assets" / "map.html"


class RouteCreatorMapView(Gtk.Box):
    def __init__(self) -> None:
        super().__init__()
        self.on_map_tapped: Callable[[float, float], None] | None = None

        self._content_manager = WebKit.UserContentManager()
        self._content_manager.register_script_message_handler("waypointTapped")
        self._content_manager.connect("script-message-received::waypointTapped", self._on_waypoint_tapped)
        self._content_manager.register_script_message_handler("pageReady")
        self._content_manager.connect("script-message-received::pageReady", self._on_page_ready)

        self.webview = WebKit.WebView(user_content_manager=self._content_manager)
        self.append(self.webview)

        self._page_loaded = False
        self._pending_scripts: list[str] = []
        self.webview.load_uri(f"file://{_MAP_HTML_PATH}")

    def _on_page_ready(self, _manager: WebKit.UserContentManager, _js_value) -> None:  # noqa: ANN001
        self._page_loaded = True
        self.webview.evaluate_javascript("window.enableCreatorMode();", -1)
        pending, self._pending_scripts = self._pending_scripts, []
        for script in pending:
            self.webview.evaluate_javascript(script, -1)

    def _run_js(self, script: str) -> None:
        if not self._page_loaded:
            self._pending_scripts.append(script)
            return
        self.webview.evaluate_javascript(script, -1)

    def _on_waypoint_tapped(self, _manager: WebKit.UserContentManager, js_value) -> None:  # noqa: ANN001
        payload = json.loads(js_value.to_string())
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
