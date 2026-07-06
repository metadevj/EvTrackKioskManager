# Release Notes

All notable changes to EvTrack Kiosk Manager are documented here.
This file is the source for GitHub release notes when publishing a release.

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
