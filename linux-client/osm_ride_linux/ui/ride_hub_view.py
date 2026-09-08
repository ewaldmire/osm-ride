"""Combines RoutesView and HistoryView under one header with a Routes/History switcher - riding
and reviewing past rides are both "the main thing you do here", so they share one bottom-nav
tab instead of two. Create/Import actions only show up while the Routes sub-tab is active.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/ride/RideHubScreen.kt.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402

from .history_view import HistoryView  # noqa: E402
from .routes_view import RoutesView  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402


class RideHubView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window

        self.routes_view = RoutesView(window)
        self.history_view = HistoryView(window)

        self._stack = Gtk.Stack()
        self._stack.add_titled(self.routes_view, "routes", "Routes")
        self._stack.add_titled(self.history_view, "history", "History")
        self._stack.connect("notify::visible-child-name", lambda _s, _p: self._update_actions_visibility())

        switcher = Gtk.StackSwitcher()
        switcher.set_stack(self._stack)

        self._create_button = Gtk.Button(label="Create Route…")
        self._create_button.connect("clicked", lambda _b: window.show_route_creator_new())
        self._import_button = Gtk.Button(label="Import GPX…")
        self._import_button.connect("clicked", lambda _b: self.routes_view.import_route())

        header = Adw.HeaderBar(title_widget=switcher)
        header.pack_end(self._create_button)
        header.pack_end(self._import_button)
        self.add_top_bar(header)

        self.set_content(self._stack)
        self._update_actions_visibility()

    def _update_actions_visibility(self) -> None:
        is_routes = self._stack.get_visible_child_name() == "routes"
        self._create_button.set_visible(is_routes)
        self._import_button.set_visible(is_routes)

    def show_routes_tab(self) -> None:
        self._stack.set_visible_child_name("routes")

    def show_history_tab(self) -> None:
        self._stack.set_visible_child_name("history")
