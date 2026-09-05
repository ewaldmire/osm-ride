"""Renders a small cached MapLibre snapshot of a route's shape, generated once at import/edit
time rather than redrawn on every list render (see route/models.py's RouteSummary.
thumbnail_file_name). Reuses the same map_assets/map.html as RideMapView/RouteCreatorMapView in
a small window sized to the thumbnail's own dimensions - GTK4 has no off-screen/headless render
path for a WebView (GTK3's Gtk.OffscreenWindow was removed), so the window must actually be
presented to render anything, snapshotted, then torn down.

Verified end-to-end against the real org.gnome.Platform//49 runtime: WebKit.WebView.get_snapshot()
still exists under that name in the GTK4 "WebKit" 6.0 API (unlike e.g. run_javascript, which was
renamed), but now returns a Gdk.Texture rather than the old GTK3 API's cairo surface -
Gdk.Texture.save_to_png() writes it directly, no Cairo conversion needed.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("WebKit", "6.0")
from gi.repository import GLib, Gtk, WebKit  # noqa: E402

from ..route.models import Route  # noqa: E402

_MAP_HTML_PATH = Path(__file__).parent / "map_assets" / "map.html"
_THUMBNAIL_WIDTH = 300
_THUMBNAIL_HEIGHT = 180
# Time to let MapLibre's own async style/tile load finish after the page itself signals ready,
# before snapshotting - matches the wait already established empirically for the ride and route
# creator map views (their style+tiles load over the network in roughly 3-4s).
_RENDER_DELAY_MS = 4000
# In case the page never signals ready at all (e.g. no network) - without this the window would
# leak forever and on_done would never fire.
_HARD_TIMEOUT_MS = 15000


def generate(route: Route, destination_path: Path, on_done: Callable[[bool], None]) -> None:
    """Fire-and-forget: on_done(success) is called exactly once, regardless of outcome."""
    window = Gtk.Window(default_width=_THUMBNAIL_WIDTH, default_height=_THUMBNAIL_HEIGHT)
    content_manager = WebKit.UserContentManager()
    content_manager.register_script_message_handler("pageReady")
    webview = WebKit.WebView(user_content_manager=content_manager)
    window.set_child(webview)
    window.present()

    state = {"finished": False}

    def finish(success: bool) -> None:
        if state["finished"]:
            return
        state["finished"] = True
        window.destroy()
        on_done(success)

    def take_snapshot() -> bool:
        def on_snapshot(_webview, gres, _data):  # noqa: ANN001
            try:
                texture = webview.get_snapshot_finish(gres)
                ok = texture.save_to_png(str(destination_path))
            except GLib.Error:
                ok = False
            finish(ok)

        webview.get_snapshot(WebKit.SnapshotRegion.VISIBLE, 0, None, on_snapshot, None)
        return False

    def on_page_ready(_manager, _value) -> None:  # noqa: ANN001
        coords = [[p.lon, p.lat] for p in route.points]
        geojson = {"type": "Feature", "geometry": {"type": "LineString", "coordinates": coords}, "properties": {}}
        webview.evaluate_javascript(f"window.setRoute({json.dumps(geojson)});", -1)
        GLib.timeout_add(_RENDER_DELAY_MS, take_snapshot)

    content_manager.connect("script-message-received::pageReady", on_page_ready)
    webview.load_uri(f"file://{_MAP_HTML_PATH}")

    GLib.timeout_add(_HARD_TIMEOUT_MS, lambda: finish(False))
