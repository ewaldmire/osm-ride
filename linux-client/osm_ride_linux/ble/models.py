"""Plain data types shared by the trainer/heart-rate BLE clients.

Mirrors app/src/main/java/com/ewaldmire/osmride/ble/TrainerData.kt.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum, auto


@dataclass(frozen=True)
class TrainerSample:
    """One parsed sample from the trainer, either from FTMS Indoor Bike Data or derived from CSC."""

    speed_mps: float | None = None
    cadence_rpm: float | None = None
    power_watts: int | None = None
    # Cumulative distance since the trainer's own counter started, when the device reports it
    # (FTMS only).
    total_distance_meters: float | None = None
    timestamp: float = field(default_factory=time.time)


@dataclass(frozen=True)
class HeartRateSample:
    bpm: int
    timestamp: float = field(default_factory=time.time)


class BleConnectionState(Enum):
    DISCONNECTED = auto()
    SCANNING = auto()
    CONNECTING = auto()
    CONNECTED = auto()


@dataclass(frozen=True)
class ScannedDevice:
    name: str
    address: str


class GradeControlState(Enum):
    """Whether the trainer is auto-adjusting resistance to match the route's simulated grade."""

    # No FTMS Control Point on this device (e.g. CSC-only trainers, or not connected).
    UNAVAILABLE = auto()
    REQUESTING = auto()
    ACTIVE = auto()
    REJECTED = auto()
