"""Connects to a BLE smart trainer and streams parsed speed/cadence/power/distance samples.

Mirrors app/src/main/java/com/ewaldmire/osmride/ble/TrainerBleManager.kt's state machine and
control-point protocol, adapted from Android's callback-based BluetoothGatt API to bleak's
asyncio API. Callers (the GTK layer) subscribe via the on_* callback attributes; this class has
no UI-framework dependency of its own.

Unlike the Android version, bleak/BlueZ handles GATT write ordering and notification/CCCD setup
internally, so there's no need for TrainerBleManager.kt's serial pendingGattOperations queue -
that was working around a limitation specific to Android's BluetoothGatt API.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from enum import Enum, auto

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic

from . import constants as c
from .models import BleConnectionState, GradeControlState, ScannedDevice, TrainerSample
from .parsing import CscParser, parse_indoor_bike_data


class _Protocol(Enum):
    FTMS = auto()
    CSC = auto()


class TrainerClient:
    def __init__(self) -> None:
        self.connection_state = BleConnectionState.DISCONNECTED
        self.grade_control_state = GradeControlState.UNAVAILABLE
        self.connected_device_name: str | None = None

        # Callbacks the UI layer sets to react to state changes - kept as simple attributes
        # rather than an event/observer framework since a GTK app is already callback-driven.
        self.on_connection_state_changed: Callable[[BleConnectionState], None] | None = None
        self.on_grade_control_state_changed: Callable[[GradeControlState], None] | None = None
        self.on_sample: Callable[[TrainerSample], None] | None = None
        self.on_device_found: Callable[[ScannedDevice], None] | None = None

        self._client: BleakClient | None = None
        self._protocol: _Protocol | None = None
        self._csc_parser = CscParser()
        self._last_sent_grade_tenths: int | None = None
        self._last_sent_target_watts: int | None = None
        self._scanner: BleakScanner | None = None
        self._simulation_task: asyncio.Task | None = None

    def _set_connection_state(self, state: BleConnectionState) -> None:
        self.connection_state = state
        if self.on_connection_state_changed:
            self.on_connection_state_changed(state)

    def _set_grade_control_state(self, state: GradeControlState) -> None:
        self.grade_control_state = state
        if self.on_grade_control_state_changed:
            self.on_grade_control_state_changed(state)

    async def scan(self, timeout: float = 6.0) -> None:
        """Scans for FTMS/CSC-advertising devices, reporting each one via on_device_found as
        it's found (rather than returning a batch), matching the live-updating scan list in the
        Android pairing screen."""
        self._set_connection_state(BleConnectionState.SCANNING)
        seen: set[str] = set()

        def _on_detection(device, advertisement_data) -> None:  # noqa: ANN001 - bleak's own types
            if device.address in seen or not device.name:
                return
            service_uuids = {u.lower() for u in (advertisement_data.service_uuids or [])}
            if c.FTMS_SERVICE not in service_uuids and c.CSC_SERVICE not in service_uuids:
                return
            seen.add(device.address)
            if self.on_device_found:
                self.on_device_found(ScannedDevice(name=device.name, address=device.address))

        async with BleakScanner(detection_callback=_on_detection):
            await asyncio.sleep(timeout)

        if self.connection_state == BleConnectionState.SCANNING:
            self._set_connection_state(BleConnectionState.DISCONNECTED)

    async def connect(self, address: str) -> None:
        self._reset_per_connection_state()
        self._set_connection_state(BleConnectionState.CONNECTING)
        try:
            client = BleakClient(address, disconnected_callback=self._on_disconnected)
            await client.connect()
        except Exception:
            self._set_connection_state(BleConnectionState.DISCONNECTED)
            return

        self._client = client
        self.connected_device_name = address
        services = client.services

        ftms_char = None
        csc_char = None
        control_char = None
        ftms_service = services.get_service(c.FTMS_SERVICE)
        if ftms_service is not None:
            ftms_char = ftms_service.get_characteristic(c.INDOOR_BIKE_DATA)
            control_char = ftms_service.get_characteristic(c.FITNESS_MACHINE_CONTROL_POINT)
        csc_service = services.get_service(c.CSC_SERVICE)
        if csc_service is not None:
            csc_char = csc_service.get_characteristic(c.CSC_MEASUREMENT)

        target = ftms_char or csc_char
        if target is None:
            await self.disconnect()
            return
        self._protocol = _Protocol.FTMS if ftms_char is not None else _Protocol.CSC
        await client.start_notify(target, self._on_data_notification)

        if control_char is not None:
            self._control_char = control_char
            self._set_grade_control_state(GradeControlState.REQUESTING)
            await client.start_notify(control_char, self._on_control_point_indication)
            await client.write_gatt_char(control_char, bytes([c.OP_REQUEST_CONTROL]), response=True)
        else:
            self._control_char = None
            self._set_grade_control_state(GradeControlState.UNAVAILABLE)

        self._set_connection_state(BleConnectionState.CONNECTED)

    async def disconnect(self) -> None:
        if self._simulation_task is not None:
            self._simulation_task.cancel()
            self._simulation_task = None
        client = self._client
        self._client = None
        self._reset_per_connection_state()
        if client is not None and client.is_connected:
            await client.disconnect()
        self._set_connection_state(BleConnectionState.DISCONNECTED)

    def _reset_per_connection_state(self) -> None:
        self._protocol = None
        self._csc_parser = CscParser()
        self._control_char = None
        self._last_sent_grade_tenths = None
        self._last_sent_target_watts = None
        self.connected_device_name = None
        self._set_grade_control_state(GradeControlState.UNAVAILABLE)

    def _on_disconnected(self, client: BleakClient) -> None:
        self._client = None
        self._set_connection_state(BleConnectionState.DISCONNECTED)

    def _on_data_notification(self, characteristic: BleakGATTCharacteristic, data: bytearray) -> None:
        sample = None
        if self._protocol == _Protocol.FTMS:
            sample = parse_indoor_bike_data(bytes(data))
        elif self._protocol == _Protocol.CSC:
            sample = self._csc_parser.parse(bytes(data))
        if sample is not None and self.on_sample:
            self.on_sample(sample)

    def _on_control_point_indication(self, characteristic: BleakGATTCharacteristic, data: bytearray) -> None:
        """Response Code indication: [0x80, echoed request op code, result code]."""
        if len(data) < 3 or data[0] != c.OP_RESPONSE_CODE:
            return
        request_op_code = data[1]
        success = data[2] == c.RESULT_SUCCESS
        if request_op_code == c.OP_REQUEST_CONTROL:
            self._set_grade_control_state(GradeControlState.ACTIVE if success else GradeControlState.REJECTED)
        elif request_op_code in (c.OP_SET_INDOOR_BIKE_SIMULATION_PARAMETERS, c.OP_SET_TARGET_POWER):
            if not success:
                self._set_grade_control_state(GradeControlState.REJECTED)

    async def set_simulated_grade(self, grade_percent: float) -> None:
        """FTMS "Set Indoor Bike Simulation Parameters" - debounced so we're not writing on
        every ~1Hz stats update for an unchanged grade."""
        client, control_char = self._client, getattr(self, "_control_char", None)
        if client is None or control_char is None or self.grade_control_state != GradeControlState.ACTIVE:
            return
        rounded_tenths = round(grade_percent * 10)
        if rounded_tenths == self._last_sent_grade_tenths:
            return
        self._last_sent_grade_tenths = rounded_tenths

        # sint16, 0.01% resolution.
        grade_raw = max(-32768, min(32767, round(grade_percent * 100)))
        grade_raw &= 0xFFFF
        # No wind simulation: sint16 wind speed left at 0. Crr (rolling resistance, uint8 @
        # 0.0001) and Cw (wind resistance, uint8 @ 0.01 kg/m) use the commonly-used road-bike
        # defaults (0.0040, 0.51) most trainer-control apps use.
        payload = bytes(
            [
                c.OP_SET_INDOOR_BIKE_SIMULATION_PARAMETERS,
                0,
                0,
                grade_raw & 0xFF,
                (grade_raw >> 8) & 0xFF,
                40,  # Crr = 0.0040
                51,  # Cw = 0.51
            ]
        )
        await client.write_gatt_char(control_char, payload, response=True)

    async def set_target_power(self, watts: int) -> None:
        """ERG mode: FTMS "Set Target Power". Mutually exclusive with set_simulated_grade for a
        given ride - callers should send one or the other, not both."""
        client, control_char = self._client, getattr(self, "_control_char", None)
        if client is None or control_char is None or self.grade_control_state != GradeControlState.ACTIVE:
            return
        if watts == self._last_sent_target_watts:
            return
        self._last_sent_target_watts = watts

        watts_raw = max(-32768, min(32767, watts)) & 0xFFFF
        payload = bytes([c.OP_SET_TARGET_POWER, watts_raw & 0xFF, (watts_raw >> 8) & 0xFF])
        await client.write_gatt_char(control_char, payload, response=True)

    async def start_simulation(self) -> None:
        """Testing helper: feeds synthetic samples on a 1Hz timer, no real BLE device involved."""
        await self.disconnect()
        self._set_connection_state(BleConnectionState.CONNECTED)
        self._set_grade_control_state(GradeControlState.ACTIVE)
        self.connected_device_name = "Simulated Trainer"

        async def _run() -> None:
            import math

            t = 0.0
            while True:
                speed_mps = 5.5 + math.sin(t / 20.0) * 1.5
                cadence_rpm = 82.0 + math.sin(t / 15.0) * 6.0
                power_watts = 150 + round(math.sin(t / 12.0) * 30.0)
                if self.on_sample:
                    self.on_sample(
                        TrainerSample(
                            speed_mps=speed_mps,
                            cadence_rpm=cadence_rpm,
                            power_watts=power_watts,
                            total_distance_meters=None,
                        )
                    )
                t += 1.0
                await asyncio.sleep(1.0)

        self._simulation_task = asyncio.ensure_future(_run())
