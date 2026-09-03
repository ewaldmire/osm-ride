"""Connects to a standard BLE Heart Rate Service (0x180D) device, e.g. a chest strap.

Mirrors app/src/main/java/com/ewaldmire/osmride/ble/HeartRateBleManager.kt, adapted from
Android's BluetoothGatt callback API to bleak's asyncio API.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic

from . import constants as c
from .models import BleConnectionState, HeartRateSample, ScannedDevice
from .parsing import parse_heart_rate_measurement


class HeartRateClient:
    def __init__(self) -> None:
        self.connection_state = BleConnectionState.DISCONNECTED
        self.connected_device_name: str | None = None

        self.on_connection_state_changed: Callable[[BleConnectionState], None] | None = None
        self.on_sample: Callable[[HeartRateSample], None] | None = None
        self.on_device_found: Callable[[ScannedDevice], None] | None = None

        self._client: BleakClient | None = None

    def _set_connection_state(self, state: BleConnectionState) -> None:
        self.connection_state = state
        if self.on_connection_state_changed:
            self.on_connection_state_changed(state)

    async def scan(self, timeout: float = 6.0) -> None:
        self._set_connection_state(BleConnectionState.SCANNING)
        seen: set[str] = set()

        def _on_detection(device, advertisement_data) -> None:  # noqa: ANN001 - bleak's own types
            if device.address in seen or not device.name:
                return
            service_uuids = {u.lower() for u in (advertisement_data.service_uuids or [])}
            if c.HEART_RATE_SERVICE not in service_uuids:
                return
            seen.add(device.address)
            if self.on_device_found:
                self.on_device_found(ScannedDevice(name=device.name, address=device.address))

        async with BleakScanner(detection_callback=_on_detection):
            await asyncio.sleep(timeout)

        if self.connection_state == BleConnectionState.SCANNING:
            self._set_connection_state(BleConnectionState.DISCONNECTED)

    async def connect(self, address: str) -> None:
        self._set_connection_state(BleConnectionState.CONNECTING)
        try:
            client = BleakClient(address, disconnected_callback=self._on_disconnected)
            await client.connect()
        except Exception:
            self._set_connection_state(BleConnectionState.DISCONNECTED)
            return

        self._client = client
        self.connected_device_name = address

        hr_service = client.services.get_service(c.HEART_RATE_SERVICE)
        hr_char = hr_service.get_characteristic(c.HEART_RATE_MEASUREMENT) if hr_service else None
        if hr_char is None:
            await self.disconnect()
            return

        await client.start_notify(hr_char, self._on_data_notification)
        self._set_connection_state(BleConnectionState.CONNECTED)

    async def disconnect(self) -> None:
        client = self._client
        self._client = None
        self.connected_device_name = None
        if client is not None and client.is_connected:
            await client.disconnect()
        self._set_connection_state(BleConnectionState.DISCONNECTED)

    def _on_disconnected(self, client: BleakClient) -> None:
        self._client = None
        self.connected_device_name = None
        self._set_connection_state(BleConnectionState.DISCONNECTED)

    def _on_data_notification(self, characteristic: BleakGATTCharacteristic, data: bytearray) -> None:
        bpm = parse_heart_rate_measurement(bytes(data))
        if bpm is not None and self.on_sample:
            self.on_sample(HeartRateSample(bpm=bpm))
