"""Small persisted settings: FTP (mirrors SettingsPrefs.kt) and the last-connected BLE device
addresses (mirrors the "ble_devices" SharedPreferences read directly in
DevicePairingViewModel.kt). Consolidated into one JSON file under $XDG_CONFIG_HOME - Python has
no equivalent to Android's per-feature SharedPreferences-file convention, and there's no reason
to split what's really just a handful of small values."""

from __future__ import annotations

import json
import os
from pathlib import Path


def _config_home() -> Path:
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg else Path.home() / ".config"
    return base / "osm-ride-linux"


class AppPrefs:
    def __init__(self, config_dir: Path | None = None) -> None:
        self._dir = config_dir or _config_home()
        self._dir.mkdir(parents=True, exist_ok=True)
        self._file = self._dir / "settings.json"
        self._data: dict = self._load()

    def get_ftp_watts(self) -> int | None:
        value = self._data.get("ftp_watts")
        return value if isinstance(value, int) and value > 0 else None

    def set_ftp_watts(self, watts: int | None) -> None:
        self._set_or_remove("ftp_watts", watts if watts and watts > 0 else None)

    def get_trainer_address(self) -> str | None:
        return self._data.get("trainer_address")

    def set_trainer_address(self, address: str | None) -> None:
        self._set_or_remove("trainer_address", address)

    def get_hr_address(self) -> str | None:
        return self._data.get("hr_address")

    def set_hr_address(self, address: str | None) -> None:
        self._set_or_remove("hr_address", address)

    def _set_or_remove(self, key: str, value) -> None:  # noqa: ANN001
        if value is None:
            self._data.pop(key, None)
        else:
            self._data[key] = value
        self._save()

    def _load(self) -> dict:
        if not self._file.exists():
            return {}
        try:
            return json.loads(self._file.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}

    def _save(self) -> None:
        self._file.write_text(json.dumps(self._data, indent=2), encoding="utf-8")
