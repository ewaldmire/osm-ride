from pathlib import Path

import pytest

from osm_ride_linux.util.app_prefs import AppPrefs


@pytest.fixture
def prefs_dir(tmp_path: Path) -> Path:
    return tmp_path / "config"


def test_ftp_watts_defaults_to_none(prefs_dir: Path):
    prefs = AppPrefs(config_dir=prefs_dir)
    assert prefs.get_ftp_watts() is None


def test_ftp_watts_round_trips(prefs_dir: Path):
    prefs = AppPrefs(config_dir=prefs_dir)
    prefs.set_ftp_watts(250)
    fresh = AppPrefs(config_dir=prefs_dir)
    assert fresh.get_ftp_watts() == 250


def test_ftp_watts_zero_or_negative_clears_it(prefs_dir: Path):
    prefs = AppPrefs(config_dir=prefs_dir)
    prefs.set_ftp_watts(250)
    prefs.set_ftp_watts(0)
    assert prefs.get_ftp_watts() is None


def test_device_addresses_round_trip(prefs_dir: Path):
    prefs = AppPrefs(config_dir=prefs_dir)
    prefs.set_trainer_address("AA:BB:CC:DD:EE:FF")
    prefs.set_hr_address("11:22:33:44:55:66")

    fresh = AppPrefs(config_dir=prefs_dir)
    assert fresh.get_trainer_address() == "AA:BB:CC:DD:EE:FF"
    assert fresh.get_hr_address() == "11:22:33:44:55:66"


def test_clearing_trainer_address_leaves_hr_address_intact(prefs_dir: Path):
    prefs = AppPrefs(config_dir=prefs_dir)
    prefs.set_trainer_address("AA:BB:CC:DD:EE:FF")
    prefs.set_hr_address("11:22:33:44:55:66")
    prefs.set_trainer_address(None)
    assert prefs.get_trainer_address() is None
    assert prefs.get_hr_address() == "11:22:33:44:55:66"
