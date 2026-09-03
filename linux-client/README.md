# OSM Ride - Linux client

A native RHEL/CentOS Stream desktop companion to the Android app, for riding with a BLE smart
trainer from a Linux machine (e.g. one wired to a TV) instead of a phone. Same trainer protocol,
same GPX/workout file formats - built as a separate GTK3 application, not a port of the Android
UI code (Jetpack Compose, MapLibre Android, and `android.bluetooth.*` are all Android-specific
and don't run outside Android; see the ported byte-level protocol logic below for what *is*
shared in spirit, if not in code).

## Stack

- **GTK3** + **WebKit2GTK 4.0** for the UI and embedded map (MapLibre GL JS via a WebKit view).
  GTK3, not GTK4: RHEL 9's only WebKitGTK package (`webkit2gtk3`) is the GTK3-era binding - there
  is no GTK4-native WebKit package in RHEL 9's repos.
- **bleak** for BLE (talks to BlueZ under the hood on Linux).
- **Flatpak** for packaging, self-hosted via a static repo on GitHub Pages - no Flathub
  submission, no Snap.

## Domain logic

`osm_ride_linux/ble/` is a faithful, tested port of
`app/src/main/java/com/ewaldmire/osmride/ble/{BleConstants,BleParsing,TrainerData,TrainerBleManager}.kt`
- same UUIDs, same FTMS/CSC byte layouts, same control-point opcodes, adapted from Android's
  callback-based `BluetoothGatt` API to bleak's asyncio API. Unlike the Android version, there's
  no manual serial GATT-write queue - that was working around a limitation specific to
  `BluetoothGatt`; bleak/BlueZ handle write ordering internally.

## Status

Scaffolding, not a complete app yet. Built and functional: the GTK3 app shell, the async↔GTK
bridge that lets `bleak` coexist with GTK's own main loop, Ride History, Settings, and Device
Pairing (including a "Simulate for testing" trainer, no hardware required). Workouts, Routes, and
Ride - the map, ERG mode, the actual point of the app - are still placeholder screens.

## Running it

PyGObject (the `gi`/GTK bindings) is a **system** package from `dnf`, not something pip can
install cleanly on RHEL - it has to link against the system GTK3 libraries. That means a plain
venv can't see it; it needs `--system-site-packages` so the venv can see system packages while
still keeping `pip install`s (bleak) out of your home directory.

```
sudo dnf install python3-gobject gtk3 python3-pip
cd linux-client
python3 -m venv --system-site-packages .venv
source .venv/bin/activate
pip install -r requirements.txt
python3 -m osm_ride_linux
```

## Development environment

RHEL 9's WebKit2GTK/GTK3/flatpak-builder toolchain was verified in a `quay.io/centos/centos:stream9`
podman container (CentOS Stream 9 tracks RHEL 9's package set closely) rather than assumed - see
git history for what was actually tested there before landing.

```
sudo dnf install python3-gobject gtk3-devel webkit2gtk3-devel flatpak flatpak-builder
pip install -r requirements.txt
python3 -m pytest tests/
```
