# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

**EvTrackKioskManager** — a small, stable **Device Owner** app for the EvTrack Android 14
kiosk fleet (Raspberry Pi 5). It manages the lifecycle of the main kiosk app
`com.evtrack.frontdesk`: install, update (with SHA-256 verify), and rollback from a
public CDN. It is deliberately minimal and rarely changed.

**v1 is MANUAL** (buttons in `MainActivity`). Server-push is **phase 2** — do NOT
implement it; keep the seam at `UpdateManager.onServerPushTrigger()` clean.

## Conventions (match sibling app EvTrackFrontDeskBeta)

- Groovy Gradle DSL (`build.gradle`, `settings.gradle` — never `.kts`).
- Gradle wrapper 8.13, AGP 8.5.x, Kotlin 1.9.x, Java 17.
- Package / namespace / applicationId: `com.evtrack.kioskmanager`.
- minSdk 26, compileSdk 34, targetSdk 34. versionCode 1, versionName "1.0.0".
- Dependencies stay MINIMAL: androidx core/appcompat/material/lifecycle +
  kotlinx-coroutines-android. **No Firebase, no Compose, no OkHttp** — plain
  `HttpURLConnection` + `org.json`, XML layouts.

## Layout

- `app/src/main/java/com/evtrack/kioskmanager/`
  - `MainActivity.kt` — manual UI (status rows + Check/Install/Beta/Rollback buttons).
  - `AdminReceiver.kt` — `DeviceAdminReceiver`; holds Device Owner.
  - `cdn/CdnClient.kt` — CDN discovery → `ReleaseMeta`.
  - `update/ApkDownloader.kt` — stream + SHA-256 verify.
  - `update/ApkInstaller.kt` — silent `PackageInstaller` install/uninstall; async→suspend bridge.
  - `update/InstallResultReceiver.kt` — receives PackageInstaller result, resolves the bridge.
  - `update/UpdateManager.kt` — orchestration + last-known-good rollback; phase-2 stubs.

## Key invariants

- **Silent install requires Device Owner.** Provision with
  `adb shell dpm set-device-owner com.evtrack.kioskmanager/.AdminReceiver` (device must
  have NO accounts). On the fleet it is baked into the OS image (EvTrackOS issue #27).
- **CDN URL scheme is shared with the web updater** — do not diverge. See `CdnClient`.
- **Rollback:** snapshot the installed APK to `filesDir/apks/last_good.apk` BEFORE
  installing; reinstall it on failure.
- `InstallResultReceiver` is `exported=false` and targeted by explicit component.

## Build

```
./gradlew assembleDebug
```

No tests/linters configured. Requires Android SDK (`local.properties` `sdk.dir` or
`ANDROID_HOME`).

## Issue map

scaffold #21 · CDN updater #22 · install+rollback #23 · watchdog #24 (stub
`checkManagedAppHealth()`) · UI #25 · server-push #26 (stub `onServerPushTrigger()`) ·
OS integration/auto-provision #27.
