"""Adw.ToolbarView is a final GObject class - it can't be subclassed (confirmed: attempting to
derive from it raises "could not create new GType" at class-definition time). Every full-screen
view in this app wants the same "header bar(s) + scrollable content" shape ToolbarView provides,
so this wraps one inside a plain Gtk.Box instead, exposing the same add_top_bar()/
add_bottom_bar()/set_content() methods, letting screen classes use it as a drop-in non-final
base without duplicating the wrapping at every call site.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gtk  # noqa: E402


class ToolbarPage(Gtk.Box):
    def __init__(self) -> None:
        super().__init__()
        self._toolbar_view = Adw.ToolbarView()
        self._toolbar_view.set_vexpand(True)
        self._toolbar_view.set_hexpand(True)
        self.append(self._toolbar_view)

    def add_top_bar(self, widget: Gtk.Widget) -> None:
        self._toolbar_view.add_top_bar(widget)

    def add_bottom_bar(self, widget: Gtk.Widget) -> None:
        self._toolbar_view.add_bottom_bar(widget)

    def set_content(self, widget: Gtk.Widget) -> None:
        self._toolbar_view.set_content(widget)
