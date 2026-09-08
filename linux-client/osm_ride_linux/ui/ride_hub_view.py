"""Combines RoutesView and HistoryView under one header with a Routes/History switcher - riding
and reviewing past rides are both "the main thing you do here", so they share one bottom-nav
tab instead of two. Create/Import actions only show up while the Routes sub-tab is active.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/ridehub/RideHubScreen.kt.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from .history_view import HistoryView  # noqa: E402
from .routes_view import RoutesView  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402

_ROUTES_SUBTITLE = "Create a route or import a GPX file, then tap it to start riding"
_HISTORY_SUBTITLE = "Review your completed rides and stats"


class RideHubView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window

        self.routes_view = RoutesView(window)
        self.history_view = HistoryView(window)

        self._stack = Gtk.Stack()
        self._stack.add_titled(self.routes_view, "routes", "Routes")
        self._stack.add_titled(self.history_view, "history", "History")
        self._stack.connect("notify::visible-child-name", lambda _s, _p: self._on_tab_changed())

        switcher = Gtk.StackSwitcher()
        switcher.set_stack(self._stack)

        self._create_button = Gtk.Button(label="Create Route…")
        self._create_button.connect("clicked", lambda _b: window.show_route_creator_new())
        self._import_button = Gtk.Button(label="Import GPX…")
        self._import_button.connect("clicked", lambda _b: self.routes_view.import_route())

        # Title+subtitle here (matching Profile/Workouts/Settings' header pattern), with the tab
        # switcher as its own row below rather than sharing the title slot - there's no room for
        # both a subtitle and a switcher in one HeaderBar's title area.
        self._window_title = Adw.WindowTitle(title="Ride", subtitle=_ROUTES_SUBTITLE)
        header = Adw.HeaderBar(title_widget=self._window_title)
        header.pack_end(self._create_button)
        header.pack_end(self._import_button)
        self.add_top_bar(header)

        switcher_row = Gtk.CenterBox()
        switcher_row.set_center_widget(switcher)
        switcher_row.add_css_class("toolbar")
        self.add_top_bar(switcher_row)

        self.set_content(self._stack)
        self._on_tab_changed()

    def _on_tab_changed(self) -> None:
        is_routes = self._stack.get_visible_child_name() == "routes"
        self._create_button.set_visible(is_routes)
        self._import_button.set_visible(is_routes)
        self._window_title.set_subtitle(_ROUTES_SUBTITLE if is_routes else _HISTORY_SUBTITLE)

    def show_routes_tab(self) -> None:
        self._stack.set_visible_child_name("routes")

    def show_history_tab(self) -> None:
        self._stack.set_visible_child_name("history")
