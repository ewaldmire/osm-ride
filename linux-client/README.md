# OSM Ride - Linux client

A desktop companion to the Android app, for riding with a BLE smart trainer from a Linux machine
(e.g. one wired to a TV) instead of a phone. Same trainer protocol, same GPX/workout file formats
- built as a separate GTK4/libadwaita application, not a port of the Android UI code (Jetpack
Compose, MapLibre Android, and `android.bluetooth.*` are all Android-specific and don't run
outside Android; see the ported byte-level protocol logic below for what *is* shared in spirit,
if not in code). Distributed exclusively as a Flatpak (see "Installing via Flatpak" and
"Development environment" below for why).

## Stack

- **GTK4** + **libadwaita** for the UI - the modern GNOME design language (boxed-list rows,
  integrated header bars, `Adw.ViewSwitcherBar` for the persistent bottom tab bar), a deliberate
  upgrade from an earlier plain-GTK3 version of this app: GTK3's own "Adwaita" theme predates
  libadwaita's 2021 visual refresh by several years and reads as dated by comparison, and
  libadwaita is GTK4-only - there's no way to get this look on GTK3.
- **WebKit** (the GTK4-native "WebKit-6.0" GIR API, not the older GTK3-era "WebKit2") for the
  embedded map (MapLibre GL JS via a WebKit view). Confirmed directly against the
  `org.gnome.Platform//49` runtime (installed and inspected, not assumed) that it ships both this
  and the GTK3 WebKit variant side by side.
- **bleak** for BLE (talks to BlueZ under the hood on Linux).
- **Flatpak** for packaging and for running the app at all, self-hosted via a static repo on
  GitHub Pages - no Flathub submission, no Snap. See `flatpak/` for the manifest and "Installing
  via Flatpak" below.

## Domain logic

`osm_ride_linux/ble/` is a faithful, tested port of
`app/src/main/java/com/ewaldmire/osmride/ble/{BleConstants,BleParsing,TrainerData,TrainerBleManager}.kt`
- same UUIDs, same FTMS/CSC byte layouts, same control-point opcodes, adapted from Android's
  callback-based `BluetoothGatt` API to bleak's asyncio API. Unlike the Android version, there's
  no manual serial GATT-write queue - that was working around a limitation specific to
  `BluetoothGatt`; bleak/BlueZ handle write ordering internally.

## Status

Full feature parity with the Android app's core screens: the GTK4/libadwaita app shell, the
async↔GTK bridge that lets `bleak` coexist with GTK's own main loop, Ride History, Settings,
Device Pairing (including a "Simulate for testing" trainer, no hardware required), Routes
(import + the Route Creator's tap-to-route-via-BRouter builder), Workouts (import + the
block-based Workout Creator), and the Ride screen itself (3D MapLibre map, ERG mode, live
stats). Packaged as a Flatpak; see "Installing via Flatpak" below.

## Running it

This app only runs as a Flatpak - there is no supported native (`dnf install` + venv) path.
That's a real constraint, not a convenience choice: checked directly against the distro repos,
neither RHEL 9 nor RHEL 10 ship a WebKit built for GTK4, and RHEL 10 dropped WebKitGTK from its
repos entirely (no GTK3 WebKit either), so the map screens have no working native install target
on this distro family. The `org.gnome.Platform` Flatpak/GNOME runtime carries its own GTK4,
libadwaita, and WebKit, sidestepping the host distro's package set entirely - see "Installing via
Flatpak" below.

## Installing via Flatpak

Self-hosted, not on Flathub - the repo is a static file tree published via GitHub Pages by
`.github/workflows/linux-release.yml` on every push to `main` that touches `linux-client/`. It's
unsigned (no GPG key), so add it with `--no-gpg-verify` rather than double-clicking the
`.flatpakref`:

```
flatpak remote-add --if-not-exists --no-gpg-verify osm-ride-linux \
    https://ewaldmire.github.io/osm-ride/flatpak-repo
flatpak install osm-ride-linux com.ewaldmire.OsmRideLinux
flatpak run com.ewaldmire.OsmRideLinux
```

A single-file `osm-ride-linux.flatpak` bundle (for `flatpak install --bundle`, no remote needed)
is also published alongside the repo.

## Development environment

The domain-logic test suite (`tests/`, BLE/GPX/workout parsing and the repositories) never
imports `gi` and needs nothing beyond `pip install -r requirements.txt`:

```
pip install -r requirements.txt
python3 -m pytest tests/
```

The UI itself has no native package set to develop against (see "Running it" above), so it's
run and smoke-tested directly against an installed `org.gnome.Platform//49` runtime instead of a
built Flatpak app - `flatpak run` can target a bare runtime for exactly this:

```
sudo dnf install flatpak
flatpak remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install flathub org.gnome.Platform//49
flatpak run --command=python3.13 --filesystem=<path to this repo> org.gnome.Platform//49 \
    -m osm_ride_linux
```

Verified this way (not assumed) in a `quay.io/centos/centos:stream9` podman container during
the GTK3→GTK4/libadwaita port: GTK4, libadwaita, and both WebKit variants ("WebKit2-4.1" for
GTK3, "WebKit-6.0" for GTK4) were confirmed present by installing the runtime and inspecting its
files directly, and every screen was exercised end-to-end this way before landing - see git
history for what was actually tested. `bleak`'s own dependencies (notably `dbus-fast`, which
ships as a compiled per-Python-version wheel) need to match the runtime's Python version, not the
host's - fetch the matching wheel explicitly if the two differ, e.g. via
`pip download --platform manylinux2014_x86_64 --python-version 313 --implementation cp --abi cp313 --only-binary=:all: dbus-fast`.

Building the actual Flatpak (`flatpak/com.ewaldmire.OsmRideLinux.yml`) needs `flatpak-builder`,
and nested Flatpak sandboxing needs privileges a default podman container doesn't have (`bwrap`
needs `--cap-add=SYS_ADMIN --cap-add=NET_ADMIN --cap-add=NET_RAW --device=/dev/fuse` at minimum) -
that verification ran in a separate throwaway privileged container, not the main dev one:

```
sudo dnf install flatpak flatpak-builder
cd flatpak
flatpak-builder --force-clean --repo=repo build-dir com.ewaldmire.OsmRideLinux.yml
```
