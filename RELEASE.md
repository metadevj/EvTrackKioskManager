# Release Notes

All notable changes to EvTrack Kiosk Manager are documented here.
This file is the source for GitHub release notes when publishing a release.

---

## [1.2.5] - 2026-10-05

### Added
- The Manager can hand Device Owner back, with a script to drive it.
- Releases are published to ARCS and registered there, so a new Kiosk Manager
  no longer needs a new OS image once self-update ships.

### Changed
- Signing material is fetched from AWS Secrets Manager per build
  (`scripts/fetch-signing-env.sh`) instead of a local `keys.sh`, which no
  longer exists on the build machine.
- `build.sh` pins JDK 17; the machine default java-21 is JRE-only and fails
  the build.

### Fixed
- The publish script refuses debug-signed APKs and ones whose versionName
  does not match `VERSION`, and never uploads after a failed existence check.

## [1.1.4] - 2026-09-15

### Added
- Install the Emirates ID build of FrontDesk as well as the standard one, on either the main or the
  beta channel: four install options instead of two.

### Changed
- Releases are now discovered through ARCS rather than the public CDN at downloads.evtrack.com.
  Publishing moved to ARCS some releases ago and that CDN was frozen from then on, so every kiosk
  has been told 2.24.5366 was the newest build while beta had reached 2.30.5374. Devices see the
  current releases again.
- A device is no longer locked down until FrontDesk says it should be. FrontDesk pins itself as soon
  as the Manager allows it to, so granting that permission on every boot was itself the lockdown,
  and it applied to kiosks nobody had paired yet. FrontDesk now decides, being the only side that
  knows whether the kiosk is paired and what the server asked for.

### Fixed
- A device that lost its FrontDesk is no longer reinstalled onto a different channel, or moved
  backwards onto an older build than the one it already had.
- A download is no longer reported as failed when the file was in fact written.

---

## [1.0.2] - 2026-07-06

### Added
- Release pipeline: `VERSION` file (single source of truth), `scripts/bump-version.sh`,
  `release.sh`, `push-release.sh`, and a GitHub Actions workflow that publishes a
  GitHub Release on every `v*` tag.
- APK naming convention `evtrack-kiosk-manager-universal-release.apk` (CDN-consistent,
  mirrorable, stable `releases/latest/download/` permalink).

### Notes
- First public GitHub release of the Device-Owner Kiosk Manager: installs / updates /
  recovers the FrontDesk kiosk app from the CDN (silent install, rollback), enforces
  kiosk lock-task lockdown, signature-gated install trigger, and first-boot bootstrap.
- Releases are cut from `master` only.
