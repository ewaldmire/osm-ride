"""Pair a BLE smart trainer and (optionally) a heart rate monitor.

Mirrors app/src/main/java/com/ewaldmire/osmride/ui/pairing/{DevicePairingScreen,
DevicePairingViewModel}.kt. Auto-reconnects to whichever device address was last saved, matching
the Kotlin ViewModel's init block.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import GLib, Gtk  # noqa: E402

from ..ble.models import BleConnectionState, ScannedDevice  # noqa: E402


class PairingView(Gtk.Box):
    def __init__(self, window) -> None:  # noqa: ANN001 - MainWindow, avoiding an import cycle
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        self.window = window
        app = window.app

        header = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        back = Gtk.Button(label="< Back")
        back.connect("clicked", lambda _b: window.show_history())
        header.pack_start(back, False, False, 0)
        header.pack_start(Gtk.Label(label="Pair Devices"), False, False, 0)

        self.set_margin_top(12)
        self.set_margin_bottom(12)
        self.set_margin_start(16)
        self.set_margin_end(16)

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

        self.pack_start(header, False, False, 0)
        self.pack_start(self.trainer_section, False, False, 0)
        self.pack_start(self.hr_section, False, False, 0)


class DeviceSection(Gtk.Box):
    def __init__(
        self,
        app,  # noqa: ANN001 - OsmRideApplication, avoiding an import cycle
        title: str,
        client,  # noqa: ANN001 - TrainerClient | HeartRateClient
        saved_address: str | None,
        on_address_changed,  # noqa: ANN001 - Callable[[str | None], None]
        allow_simulate: bool,
    ) -> None:
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        self._app = app
        self._client = client
        self._on_address_changed = on_address_changed
        self._allow_simulate = allow_simulate
        self._devices: list[ScannedDevice] = []
        self._scan_future = None

        title_label = Gtk.Label(label=title, xalign=0.0)
        title_label.get_style_context().add_class("heading")
        self._status_label = Gtk.Label(xalign=0.0)

        self._button_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self._device_list_box = Gtk.ListBox()
        self._device_list_box.set_selection_mode(Gtk.SelectionMode.NONE)

        self.pack_start(title_label, False, False, 0)
        self.pack_start(self._status_label, False, False, 0)
        self.pack_start(self._button_box, False, False, 4)
        self.pack_start(self._device_list_box, False, False, 0)

        client.on_connection_state_changed = lambda _s: GLib.idle_add(self._refresh)
        client.on_device_found = lambda d: GLib.idle_add(self._on_device_found, d)

        self._refresh()
        if saved_address:
            self._connect(saved_address)

    def _refresh(self) -> None:
        state = self._client.connection_state
        connected_name = self._client.connected_device_name
        status_text = f"{connected_name} · {state.name}" if connected_name else state.name
        self._status_label.set_text(status_text)

        for child in list(self._button_box.get_children()):
            self._button_box.remove(child)

        if state == BleConnectionState.CONNECTED:
            disconnect_button = Gtk.Button(label="Disconnect")
            disconnect_button.connect("clicked", lambda _b: self._disconnect())
            self._button_box.pack_start(disconnect_button, False, False, 0)
        elif state == BleConnectionState.SCANNING:
            spinner = Gtk.Spinner()
            spinner.start()
            stop_button = Gtk.Button(label="Stop Scan")
            stop_button.connect("clicked", lambda _b: self._stop_scan())
            self._button_box.pack_start(spinner, False, False, 0)
            self._button_box.pack_start(stop_button, False, False, 0)
        else:
            scan_button = Gtk.Button(label="Scan")
            scan_button.connect("clicked", lambda _b: self._start_scan())
            self._button_box.pack_start(scan_button, False, False, 0)
            if self._allow_simulate:
                simulate_button = Gtk.Button(label="Simulate for testing")
                simulate_button.connect("clicked", lambda _b: self._simulate())
                self._button_box.pack_start(simulate_button, False, False, 0)

        for child in list(self._device_list_box.get_children()):
            self._device_list_box.remove(child)
        if self._devices and state != BleConnectionState.CONNECTED:
            for device in self._devices:
                self._device_list_box.add(self._build_device_row(device))

        self.show_all()

    def _build_device_row(self, device: ScannedDevice) -> Gtk.ListBoxRow:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        box.set_margin_top(6)
        box.set_margin_bottom(6)
        box.set_margin_start(8)
        box.set_margin_end(8)
        name_label = Gtk.Label(label=device.name, xalign=0.0)
        address_label = Gtk.Label(label=device.address, xalign=0.0)
        address_label.get_style_context().add_class("dim-label")
        box.pack_start(name_label, False, False, 0)
        box.pack_start(address_label, False, False, 0)

        button = Gtk.Button()
        button.add(box)
        button.set_relief(Gtk.ReliefStyle.NONE)
        button.connect("clicked", lambda _b: self._connect(device.address))

        row = Gtk.ListBoxRow()
        row.add(button)
        return row

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
