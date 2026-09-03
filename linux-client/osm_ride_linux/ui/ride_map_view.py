"""Embeds the MapLibre GL JS map (map_assets/map.html) in a WebKit2 WebView and exposes a small
Python API over it, mirroring the role BikeMapView.kt plays for the Android app - route line +
bike marker for now; camera-follow, tilt, and 3D buildings come in a later pass.

JS calls made before the page finishes loading are queued and flushed once it has - the page
load is asynchronous and there's no guarantee the Python side won't call set_route() before
map.html's own 'load' handler has run.
"""

from __future__ import annotations

import json
from pathlib import Path

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("WebKit2", "4.0")
from gi.repository import Gtk, WebKit2  # noqa: E402

from ..route.models import Route  # noqa: E402

_MAP_HTML_PATH = Path(__file__).parent / "map_assets" / "map.html"


class RideMapView(Gtk.Box):
    def __init__(self) -> None:
        super().__init__()
        self.webview = WebKit2.WebView()
        self.pack_start(self.webview, True, True, 0)

        self._page_loaded = False
        self._pending_scripts: list[str] = []
        self.webview.connect("load-changed", self._on_load_changed)
        self.webview.load_uri(f"file://{_MAP_HTML_PATH}")

    def _on_load_changed(self, _webview: WebKit2.WebView, event: WebKit2.LoadEvent) -> None:
        if event != WebKit2.LoadEvent.FINISHED:
            return
        self._page_loaded = True
        pending, self._pending_scripts = self._pending_scripts, []
        for script in pending:
            self.webview.run_javascript(script, None, None, None)

    def _run_js(self, script: str) -> None:
        if not self._page_loaded:
            self._pending_scripts.append(script)
            return
        self.webview.run_javascript(script, None, None, None)

    def set_route(self, route: Route) -> None:
        coords = [[p.lon, p.lat] for p in route.points]
        geojson = {"type": "Feature", "geometry": {"type": "LineString", "coordinates": coords}, "properties": {}}
        # json.dumps produces valid JS object-literal syntax directly (JSON is a syntactic
        # subset of JS), so this can be interpolated straight into the call with no JSON.parse
        # needed on the JS side.
        self._run_js(f"window.setRoute({json.dumps(geojson)});")

    def update_bike_position(self, lon: float, lat: float) -> None:
        self._run_js(f"window.updateBikePosition({lon}, {lat});")

    def follow_bike(self, lon: float, lat: float, zoom: float, bearing: float) -> None:
        self._run_js(f"window.followBike({lon}, {lat}, {zoom}, {bearing});")
