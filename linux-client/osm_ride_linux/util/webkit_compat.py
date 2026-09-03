"""WebKit2GTK's GObject-Introspection namespace comes in two versions with an identical GTK3
API surface (WebView, UserContentManager, run_javascript, script message handlers, load events)
- the version bump between them is only about which libsoup they're built against:

- "4.0": paired with libsoup2, what RHEL 9's system `webkit2gtk3` package ships. Used for local
  development against the system libraries (see README.md's venv setup).
- "4.1": paired with libsoup3, what the org.gnome.Platform Flatpak runtime ships - GNOME dropped
  the 4.0 build once libsoup3 became the default, so it's not available there.

Trying 4.1 first and falling back to 4.0 lets the same source run unmodified in both places,
verified directly against org.gnome.Platform//49's own WebKit2-4.1 typelib (see git history/PR
notes for how) - not just assumed from the version numbers.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "3.0")
try:
    gi.require_version("WebKit2", "4.1")
except ValueError:
    gi.require_version("WebKit2", "4.0")

from gi.repository import WebKit2  # noqa: E402,F401
