# OSM Ride

An Android app for indoor cycling trainers: pick a preplanned GPX route, connect your smart
trainer (and optionally a heart rate monitor) over Bluetooth LE, and watch a bike avatar move
along the route as you pedal. Tracks distance, time, speed, cadence, power, and heart rate, and
exports a Strava-importable GPX of the completed ride.

## How it works

- **Routes**: import `.gpx` files (exported from RideWithGPS, Strava routes, komoot, etc.) via
  the in-app file picker. No routing backend — bring your own preplanned route.
- **Trainer connection**: scans for BLE devices advertising the Fitness Machine Service (FTMS,
  the modern standard most smart trainers speak) or, as a fallback, the Cycling Speed and
  Cadence (CSC) service.
- **Heart rate**: optionally pair a standard BLE heart rate strap/armband (HRS 0x180D).
- **Progress**: trainer distance (or integrated speed, for CSC-only devices) is mapped onto the
  route polyline to move the avatar and compute % complete.
- **Export**: after a ride, export/share a GPX file (with heart rate and cadence extensions)
  that Strava and most other fitness apps can import directly.

## Building

Requires a JDK 17 and the Android SDK (compileSdk/targetSdk 35). From the project root:

```
./gradlew assembleDebug     # debug build, applicationId suffix .debug
./gradlew assembleRelease   # release build type, debug-signed (see below)
```

The release build type is intentionally signed with the standard Android debug keystore rather
than a dedicated release key — this is a personal test app, not a Play Store release, so there's
no need to manage a signing secret.

## CI releases + Obtainium

`.github/workflows/release.yml` builds a release APK on every push to `main` (or via manual
"Run workflow"), tags it with the current UTC datetime (e.g. `v2026.09.02-2130`), and publishes
it as a GitHub Release with the APK attached. `versionCode` is derived from the commit count so
each build installs cleanly as an update over the last.

To track it with [Obtainium](https://github.com/ImranR98/Obtainium) on your phone, add this repo
as an "Add App" source using the GitHub Releases source — Obtainium will pick up the latest
release's APK automatically on each new push.

## Known limitations (v1)

- GPX import only — no in-app route builder/routing.
- Ride progress lives in the screen's ViewModel, not the foreground service; if Android kills
  the app process entirely while backgrounded mid-ride (rare, but possible under memory
  pressure), that ride's progress is lost. The BLE connection + notification staying alive via
  the foreground service is what makes screen-off during a normal ride safe.
- Wheel circumference for the CSC-fallback distance calculation is a fixed constant
  (`BleConstants.DEFAULT_WHEEL_CIRCUMFERENCE_METERS`, 700x25c default) rather than a settings
  screen — most FTMS trainers report distance directly and don't need it.
