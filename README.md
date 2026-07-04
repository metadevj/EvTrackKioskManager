# EvTrack Kiosk Manager

A small, stable **Device Owner** app that manages the lifecycle of the main EvTrack
kiosk app (`com.evtrack.frontdesk`) on an Android 14 Raspberry Pi 5 kiosk fleet:
**install, update, verify, and recover** it from a public CDN.

This app is deliberately simple and rarely changed. **v1 is manual** — you drive it
with buttons in the UI. Server-push (phase 2) is not implemented; a clean seam is left
for it (see `UpdateManager.onServerPushTrigger()`).

## Why a separate manager app?

The manager holds Device Owner so it can **silently** install/update/uninstall the
managed kiosk app via `PackageInstaller` (no user prompt). Keeping this in a tiny,
stable app means the frequently-updated kiosk app never needs elevated privileges and
can be safely replaced/rolled back from outside itself.

## Architecture

```
MainActivity ──▶ UpdateManager ──┬─▶ CdnClient      (discover latest/beta on CDN)
                                 ├─▶ ApkDownloader  (stream + SHA-256 verify)
                                 └─▶ ApkInstaller   (silent install as Device Owner)
                                          ▲
                          InstallResultReceiver (async PackageInstaller result)
AdminReceiver : DeviceAdminReceiver  (holds Device Owner)
```

- **Last-known-good rollback:** before each install `UpdateManager` snapshots the
  currently-installed APK to `filesDir/apks/last_good.apk`; on install failure it
  reinstalls that snapshot.
- **Silent-install bridge:** `PackageInstaller.commit()` is async. `ApkInstaller`
  parks a `CompletableDeferred` keyed by session id; `InstallResultReceiver` (targeted
  explicitly by component, `exported=false`) resolves it when the result broadcast
  arrives, letting `install()`/`uninstall()` be plain `suspend` functions.

## CDN scheme

Base URL: `https://downloads.evtrack.com/public/apk` (overridable via `CdnClient`
constructor).

- Pointer JSON (per variant `latest` | `beta`):
  `"$base/evtrack-front-desk/$variant/evtrack-front-desk-universal-release.json?r=<nanoTime>"`
  → `{ "apps": [ { "version", "build", "sha256"? } ] }`, take `apps[0]`.
- Resolved APK:
  `"$base/evtrack-front-desk/$version.$build/evtrack-front-desk-universal-release.apk"`

## Build

```
./gradlew assembleDebug
```

Requires an Android SDK (compileSdk 34). Set `sdk.dir` in `local.properties` or
`ANDROID_HOME`. Kotlin 1.9.x / AGP 8.5.x / Gradle 8.13 / Java 17.

## Device Owner provisioning

Device Owner can **only** be set on a device with **no accounts added** (fresh / factory
state). Install the APK, then:

```
adb shell dpm set-device-owner com.evtrack.kioskmanager/.AdminReceiver
```

To clear (for testing): `adb shell dpm remove-active-admin com.evtrack.kioskmanager/.AdminReceiver`.

On the shipped kiosk fleet this will also be **baked into the OS image and
auto-provisioned** (EvTrackOS issue #27), so the adb step is for dev/manual bring-up.

## Source map → GitHub issues

| Area                              | Files                                                        | Issue |
|-----------------------------------|-------------------------------------------------------------|-------|
| Project scaffold                  | Gradle files, manifest, icons, theme                        | #21   |
| CDN updater / discovery           | `cdn/CdnClient.kt`                                           | #22   |
| Install + rollback                | `update/ApkDownloader.kt`, `ApkInstaller.kt`, `InstallResultReceiver.kt`, `UpdateManager.kt` | #23 |
| Watchdog / crash-loop recovery    | `UpdateManager.checkManagedAppHealth()` (stub)              | #24   |
| Manual UI                         | `MainActivity.kt`, `res/layout/activity_main.xml`           | #25   |
| Server-push trigger               | `UpdateManager.onServerPushTrigger()` (stub)                | #26   |
| OS integration / auto-provision   | (packaging into EvTrackOS image)                            | #27   |
