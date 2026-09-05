"""Pair a BLE smart trainer and (optionally) a heart rate monitor.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/pairing/{DevicePairingScreen,
DevicePairingViewModel}.kt. Auto-reconnects to whichever device address was last saved, matching
the Kotlin ViewModel's init block.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, GLib, Gtk  # noqa: E402

from ..ble.models import BleConnectionState, ScannedDevice  # noqa: E402
from .toolbar_page import ToolbarPage  # noqa: E402


class PairingView(ToolbarPage):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__()
        self.window = window
        app = window.app

        header = Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Pair Devices"))
        back = Gtk.Button(icon_name="go-previous-symbolic")
        back.connect("clicked", lambda _b: window.show_settings())
        header.pack_start(back)
        self.add_top_bar(header)

        page = Adw.PreferencesPage()

        self.trainer_section = DeviceSection(
            app=app,
            title="Smart Trainer",
            client=app.trainer_client,
            saved_address=app.prefs.get_trainer_address(),
            on_address_changed=app.prefs.set_trainer_address,
            allow_simulate=True,
        )
        self.hr_section = DeviceSection(
            app=app,
            title="Heart Rate Monitor",
            client=app.heart_rate_client,
            saved_address=app.prefs.get_hr_address(),
            on_address_changed=app.prefs.set_hr_address,
            allow_simulate=False,
        )

        page.add(self.trainer_section.group)
        page.add(self.hr_section.group)
        self.set_content(page)


class DeviceSection:
    def __init__(
        self,
        app,  # noqa: ANN001 - OsmRideApplication, avoiding an import cycle
        title: str,
        client,  # noqa: ANN001 - TrainerClient | HeartRateClient
        saved_address: str | None,
        on_address_changed,  # noqa: ANN001 - Callable[[str | None], None]
        allow_simulate: bool,
    ) -> None:
        self._app = app
        self._client = client
        self._on_address_changed = on_address_changed
        self._allow_simulate = allow_simulate
        self._devices: list[ScannedDevice] = []
        self._scan_future = None

        self.group = Adw.PreferencesGroup(title=title)
        self._status_row = Adw.ActionRow()
        self._button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8, valign=Gtk.Align.CENTER)
        self._status_row.add_suffix(self._button_box)
        self.group.add(self._status_row)
        self._device_rows: list[Adw.ActionRow] = []

        client.on_connection_state_changed = lambda _s: GLib.idle_add(self._refresh)
        client.on_device_found = lambda d: GLib.idle_add(self._on_device_found, d)

        self._refresh()
        if saved_address:
            self._connect(saved_address)

    def _refresh(self) -> None:
        state = self._client.connection_state
        connected_name = self._client.connected_device_name
        self._status_row.set_title(connected_name if connected_name else "Not connected")
        self._status_row.set_subtitle(state.name)

        child = self._button_box.get_first_child()
        while child is not None:
            next_child = child.get_next_sibling()
            self._button_box.remove(child)
            child = next_child

        if state == BleConnectionState.CONNECTED:
            disconnect_button = Gtk.Button(label="Disconnect")
            disconnect_button.connect("clicked", lambda _b: self._disconnect())
            self._button_box.append(disconnect_button)
        elif state == BleConnectionState.SCANNING:
            spinner = Adw.Spinner()
            spinner.set_size_request(16, 16)
            stop_button = Gtk.Button(label="Stop Scan")
            stop_button.connect("clicked", lambda _b: self._stop_scan())
            self._button_box.append(spinner)
            self._button_box.append(stop_button)
        else:
            scan_button = Gtk.Button(label="Scan")
            scan_button.connect("clicked", lambda _b: self._start_scan())
            self._button_box.append(scan_button)
            if self._allow_simulate:
                simulate_button = Gtk.Button(label="Simulate for testing")
                simulate_button.connect("clicked", lambda _b: self._simulate())
                self._button_box.append(simulate_button)

        for row in self._device_rows:
            self.group.remove(row)
        self._device_rows = []
        if self._devices and state != BleConnectionState.CONNECTED:
            for device in self._devices:
                row = Adw.ActionRow(title=device.name, subtitle=device.address, activatable=True)
                row.connect("activated", lambda _r, d=device: self._connect(d.address))
                self.group.add(row)
                self._device_rows.append(row)

    def _start_scan(self) -> None:
        self._devices = []
        self._scan_future = self._app.async_bridge.submit(
            self._client.scan(), marshal=GLib.idle_add, on_done=lambda _r: self._refresh()
        )

    def _stop_scan(self) -> None:
        if self._scan_future is not None:
            self._scan_future.cancel()
            self._scan_future = None

    def _on_device_found(self, device: ScannedDevice) -> None:
        self._devices.append(device)
        self._refresh()

    def _connect(self, address: str) -> None:
        self._app.async_bridge.submit(
            self._client.connect(address),
            marshal=GLib.idle_add,
            on_done=lambda _r: self._on_connected(address),
        )

    def _on_connected(self, address: str) -> None:
        if self._client.connection_state == BleConnectionState.CONNECTED:
            self._on_address_changed(address)
        self._refresh()

    def _disconnect(self) -> None:
        self._app.async_bridge.submit(
            self._client.disconnect(), marshal=GLib.idle_add, on_done=lambda _r: self._refresh()
        )
        self._on_address_changed(None)

    def _simulate(self) -> None:
        self._app.async_bridge.submit(
            self._client.start_simulation(), marshal=GLib.idle_add, on_done=lambda _r: self._refresh()
        )
        self._on_address_changed(None)
