"""Embeds the MapLibre GL JS map (map_assets/map.html) in a WebKit WebView and exposes a small
Python API over it, mirroring the role BikeMapView.kt plays for the Android app: route line, bike
marker, camera-follow with tilt/3D buildings/padding, and sticking a manual gesture override until
a UI control resets it.

Uses WebKitGTK's GTK4 API ("WebKit" 6.0, GIR-versioned separately from the older GTK3 "WebKit2"
4.x bindings) - confirmed against org.gnome.Platform//49's own WebKit-6.0 typelib that
run_javascript() was replaced by evaluate_javascript() and script-message-received handlers now
receive a JSC.Value directly instead of a WebKitJavascriptResult wrapper.

JS calls made before the page is ready are queued and flushed once it is. Readiness is signalled
by the page itself via a "pageReady" script message (see map_assets/map.html) rather than
WebKit's own "load-changed" FINISHED signal or by polling via evaluate_javascript() - both were
tried and found unreliable: FINISHED can fire before map.html's own inline script has actually
finished running, and evaluate_javascript() calls made in that window can race ahead of it
(observed directly, reproducibly, even after multi-second waits) rather than waiting for it.
Only the page's own script can know for certain when it's done, hence the push notification.
"""

from __future__ import annotations

import json
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("WebKit", "6.0")
from gi.repository import Gtk, WebKit  # noqa: E402

from ..route.models import Route  # noqa: E402

_MAP_HTML_PATH = Path(__file__).parent / "map_assets" / "map.html"


class RideMapView(Gtk.Box):
    def __init__(self) -> None:
        super().__init__(hexpand=True, vexpand=True)

        self._content_manager = WebKit.UserContentManager()
        self._content_manager.register_script_message_handler("pageReady")
        self._content_manager.connect("script-message-received::pageReady", self._on_page_ready)

        # GTK4's Box.append() has no expand/fill arguments the way GTK3's pack_start() did -
        # without explicitly setting these, the WebView reports its own small natural size and
        # the whole map area (and this Box, and the Overlay it sits in as RideView's main child)
        # collapses down to that instead of filling the window.
        self.webview = WebKit.WebView(user_content_manager=self._content_manager, hexpand=True, vexpand=True)
        self.append(self.webview)

        self._page_loaded = False
        self._pending_scripts: list[str] = []
        self.webview.load_uri(f"file://{_MAP_HTML_PATH}")

    def _on_page_ready(self, _manager: WebKit.UserContentManager, _js_value) -> None:  # noqa: ANN001
        self._page_loaded = True
        pending, self._pending_scripts = self._pending_scripts, []
        for script in pending:
            self.webview.evaluate_javascript(script, -1)

    def _run_js(self, script: str) -> None:
        if not self._page_loaded:
            self._pending_scripts.append(script)
            return
        self.webview.evaluate_javascript(script, -1)

    def set_route(self, route: Route) -> None:
        coords = [[p.lon, p.lat] for p in route.points]
        geojson = {"type": "Feature", "geometry": {"type": "LineString", "coordinates": coords}, "properties": {}}
        # json.dumps produces valid JS object-literal syntax directly (JSON is a syntactic
        # subset of JS), so this can be interpolated straight into the call with no JSON.parse
        # needed on the JS side.
        self._run_js(f"window.setRoute({json.dumps(geojson)});")

    def update_bike_position(self, lon: float, lat: float) -> None:
        self._run_js(f"window.updateBikePosition({lon}, {lat});")

    def follow_bike(self, lon: float, lat: float, zoom: float, bearing: float, tilt_degrees: float) -> None:
        self._run_js(f"window.followBike({lon}, {lat}, {zoom}, {bearing}, {tilt_degrees});")

    def reset_manual_override(self) -> None:
        self._run_js("window.resetManualOverride();")
