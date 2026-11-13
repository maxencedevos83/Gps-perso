# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Personal, ad-free Android GPS (Kotlin, single `:app` module, package `fr.maxence.gpsperso`): Google-Maps-style guidance plus Waze-style alerts, also running on Android Auto. Strictly personal use, distributed as a sideloaded APK. User-facing text, comments and commit messages are in French.

## Build and release

- Build: `./gradlew assembleRelease` (debug: `./gradlew assembleDebug`). JDK 17, AGP 8.11, Kotlin 2.2, compile/target SDK 36, min SDK 26.
- There are no tests and no lint config.
- CI (`.github/workflows/build.yml`) builds on every push to `main` and on PRs. On `main` it publishes a GitHub release `build-<run_number>` with `gps-perso.apk`; `versionCode` = `GITHUB_RUN_NUMBER`, `versionName` = `0.1.<run>`.
- Cloud sessions usually cannot build locally (Google Maven / third-party APIs blocked): iterate through CI and check the workflow run.
- `.github/workflows/probe.yml` is a manual (`workflow_dispatch`) script for probing data sources (e.g. Valhalla) from GitHub's network.
- Signing: fixed keystore `app/gps-perso.jks` with plaintext password, intentionally, so each APK installs over the previous one. Don't change it.
- API keys are never written in code: `LUFOP_KEY` and `GOOGLE_KEY` come from GitHub secrets into `BuildConfig` at build time; other keys (TomTom, HERE, Google) can be typed in the in-app Settings.

## Architecture

- **`Nav` (Nav.kt) is the core**: a process-wide singleton that owns location (FusedLocationProvider, raw GPS fallback), the current `Route`, rerouting, voice prompts, alerts (radars/incidents) and speed limits. UIs never drive guidance themselves; they implement `Nav.Listener` (onLocation, onRouteReady, onProgress, onAlert, onSpeedLimit, …) and register with Nav. Three listeners exist:
  - `MainActivity` (phone UI, very large file: map, menus, sheets, settings screens, help text),
  - `NavService` (foreground location service + notification that keeps guidance alive with the screen locked),
  - `CarApp.kt` / `CarMap.kt` (Android Auto via Car App Library: `CarApp.kt` holds the template screens, `CarMap.kt` renders a MapLibre `MapView` into a `VirtualDisplay`/`Presentation` on the car surface).
  A guidance feature normally lands in Nav and is then surfaced in both the phone and Android Auto UIs.
- **Threading**: background work goes through `Bg.pool(name, n)` (Bg.kt), whose threads catch exceptions and record a crash report shown at next launch instead of killing the app. Nav keeps separate pools (`io`, `routeIo`, `osmIo`) so slow Overpass requests never block rerouting; keep that separation.
- **Data sources** (all free/public, one file each): `Net.kt` (HTTP helper, Photon search, Valhalla routing with alternates/tolls/lanes), `TomTom.kt` and `Here.kt` (traffic, HERE speed limits and toll prices, optional keys), `BisonFute.kt` (DATEX II incidents), `SpeedLimit.kt` (HERE > Valhalla `trace_attributes` > Overpass fallback), `Nearby.kt` (Overpass stations/restaurants, fuel prices from data.economie.gouv.fr, optional Google Places), `Weather.kt` (Open-Meteo), `Lufop.kt` (radar import from API or CSV/GPX/KML/OV2/ZIP files). Bundled radar bases live in `app/src/main/assets/radars/`.
- **Geometry and models**: `Geo.kt` holds `Route`, `Maneuver`, `Lane`, route projection and auto-zoom.
- **Persistence**: plain `SharedPreferences` wrapper classes (`Settings`, `AlertStore`, `PoiStore`, `RecentStore`, `LufopStore`). New settings go into `Settings.kt` as properties.
- **UI**: no Fragments, no Compose; views are built in code. `Sheet.kt` is the reusable bottom-sheet builder used for every menu and detail panel. Custom guidance views (junction view, lanes, radar banner) are in `NavViews.kt`. Map is MapLibre with OpenFreeMap tiles (dark style at night via `Sun.kt`); offline areas via `Offline.kt`.

## Conventions

- When shipping a user-visible change, add a line at the top of the "Nouveautés" list in `MainActivity.showHelp()`; the help sheet opens automatically after an update (`settings.seenVersion`).
- Known hard limits (don't try to implement): no RDS-TMC on phones, no public Waze alerts API. Android Auto only shows the app if installed in a Play-like way (KingInstaller), not because of code.
