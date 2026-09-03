"""Routes a sequence of tapped waypoints onto real roads/paths using BRouter's free public
routing API (https://brouter.de) - same technique as
app/src/main/java/com/ewaldmire/osmride/route/BRouterClient.kt.

Synchronous (stdlib urllib, no extra dependency) - callers on an asyncio event loop (the GTK UI)
should wrap calls with `await asyncio.to_thread(route, waypoints)` to avoid blocking.
"""

from __future__ import annotations

import urllib.error
import urllib.parse
import urllib.request

from .models import RouteWaypoint

_BASE_URL = "https://brouter.de/brouter"
_PROFILE = "trekking"
_CONNECT_TIMEOUT_SECONDS = 30


class BRouterError(Exception):
    pass


def route(waypoints: list[RouteWaypoint]) -> str:
    """Returns the raw routed GPX text following roads through `waypoints`, in order."""
    if len(waypoints) < 2:
        raise BRouterError("Need at least 2 waypoints to route")

    lonlats = "|".join(f"{wp.lon},{wp.lat}" for wp in waypoints)
    query = urllib.parse.urlencode(
        {"lonlats": lonlats, "profile": _PROFILE, "alternativeidx": 0, "format": "gpx"}
    )
    url = f"{_BASE_URL}?{query}"

    try:
        with urllib.request.urlopen(url, timeout=_CONNECT_TIMEOUT_SECONDS) as response:
            return response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise BRouterError(f"BRouter request failed ({e.code}): {error_body}") from e
    except urllib.error.URLError as e:
        raise BRouterError(f"BRouter request failed: {e.reason}") from e
