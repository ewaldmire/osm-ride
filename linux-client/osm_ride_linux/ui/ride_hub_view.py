"""Route planning: create a route in-app or import a GPX file, then tap one to start riding.
Completed-activity history used to share this screen behind a Routes/History tab switcher, but
moved to Profile's Activities feed (see activities_view.py) once outdoor .fit imports could be any
activity type, not just cycling - "Ride" is now just about riding, not reviewing the past.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/ridehub/RideHubScreen.kt.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from .routes_view import RoutesView  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402


class RideHubView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window

        self.routes_view = RoutesView(window)

        create_button = Gtk.Button(label="Create Route…")
        create_button.connect("clicked", lambda _b: window.show_route_creator_new())
        import_button = Gtk.Button(label="Import GPX…")
        import_button.connect("clicked", lambda _b: self.routes_view.import_route())

        header = Adw.HeaderBar(
            title_widget=Adw.WindowTitle(
                title="Ride", subtitle="Create a route or import a GPX file, then tap it to start riding"
            )
        )
        header.pack_end(create_button)
        header.pack_end(import_button)
        self.add_top_bar(header)

        self.set_content(self.routes_view)
