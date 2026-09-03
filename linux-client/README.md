# OSM Ride - Linux client

A native RHEL/CentOS Stream desktop companion to the Android app, for riding with a BLE smart
trainer from a Linux machine (e.g. one wired to a TV) instead of a phone. Same trainer protocol,
same GPX/workout file formats - built as a separate GTK3 application, not a port of the Android
UI code (Jetpack Compose, MapLibre Android, and `android.bluetooth.*` are all Android-specific
and don't run outside Android; see the ported byte-level protocol logic below for what *is*
shared in spirit, if not in code).

## Stack

- **GTK3** + **WebKit2GTK** for the UI and embedded map (MapLibre GL JS via a WebKit view). GTK3,
  not GTK4: RHEL 9's only WebKitGTK package (`webkit2gtk3`) is the GTK3-era binding - there is no
  GTK4-native WebKit package in RHEL 9's repos. WebKit2GTK itself comes in two GIR versions with
  an identical GTK3 API surface - "4.0" (RHEL 9's system package, paired with libsoup2) and "4.1"
  (what the Flatpak runtime ships, paired with libsoup3). `util/webkit_compat.py` tries 4.1 first
  and falls back to 4.0 so the same source runs unmodified in both places - see that file's
  docstring for how this was verified against the real Flatpak runtime, not just assumed.
- **bleak** for BLE (talks to BlueZ under the hood on Linux).
- **Flatpak** for packaging, self-hosted via a static repo on GitHub Pages - no Flathub
  submission, no Snap. See `flatpak/` for the manifest and "Installing via Flatpak" below.

## Domain logic

`osm_ride_linux/ble/` is a faithful, tested port of
`app/src/main/java/com/ewaldmire/osmride/ble/{BleConstants,BleParsing,TrainerData,TrainerBleManager}.kt`
- same UUIDs, same FTMS/CSC byte layouts, same control-point opcodes, adapted from Android's
  callback-based `BluetoothGatt` API to bleak's asyncio API. Unlike the Android version, there's
  no manual serial GATT-write queue - that was working around a limitation specific to
  `BluetoothGatt`; bleak/BlueZ handle write ordering internally.

## Status

Full feature parity with the Android app's core screens: the GTK3 app shell, the async↔GTK
bridge that lets `bleak` coexist with GTK's own main loop, Ride History, Settings, Device Pairing
(including a "Simulate for testing" trainer, no hardware required), Routes (import + the Route
Creator's tap-to-route-via-BRouter builder), Workouts (import + the block-based Workout
Creator), and the Ride screen itself (3D MapLibre map, ERG mode, live stats). Packaged as a
Flatpak; see "Installing via Flatpak" below.

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

RHEL 9's WebKit2GTK/GTK3/flatpak-builder toolchain was verified in a `quay.io/centos/centos:stream9`
podman container (CentOS Stream 9 tracks RHEL 9's package set closely) rather than assumed - see
git history for what was actually tested there before landing. The Flatpak manifest itself
(`flatpak/com.ewaldmire.OsmRideLinux.yml`) was built end-to-end with `flatpak-builder` against
the real `org.gnome.Platform`/`org.gnome.Sdk` runtimes before being wired into CI - nested Flatpak
sandboxing needs privileges a default podman container doesn't have (`bwrap` needs
`--cap-add=SYS_ADMIN --cap-add=NET_ADMIN --cap-add=NET_RAW --device=/dev/fuse` at minimum), so
that verification ran in a separate throwaway privileged container, not the main dev one.

```
sudo dnf install python3-gobject gtk3-devel webkit2gtk3-devel flatpak flatpak-builder
pip install -r requirements.txt
python3 -m pytest tests/
```
