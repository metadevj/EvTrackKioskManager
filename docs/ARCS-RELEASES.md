# Kiosk Manager releases in ARCS

ARCS (https://arcs.evtrack.com) hosts Kiosk Manager builds next to FrontDesk. Kiosks fetch the
catalogue with the same fleet licence and `X-Arcs-Proof` they already use for FrontDesk, so a new
Kiosk Manager no longer needs a new OS image.

Design: EvTrackARCS `docs/superpowers/specs/2026-10-05-kiosk-manager-os-releases-design.md`.

## One-time setup

1. Your IAM user `janzwiegers` is in the group `evtrack-kiosk-release-publishers`. It can upload to
   `s3://evtrack-releases/releases/evtrack-kiosk-manager/` and `.../evtrack-os-rpi5/`, read the
   ARCS registration token, and can never delete anything.
2. Create a CLI profile for it (any name; `evtrack-jan` below):
   ```bash
   aws configure --profile evtrack-jan     # region eu-central-1
   aws sts get-caller-identity --profile evtrack-jan
   ```
3. Tools on PATH: `aws` (v2), `jq`, `curl`, and from Android build-tools `apksigner` and `aapt2`.

## Publishing a release

```bash
./build.sh --clean
./release.sh && ./push-release.sh                    # GitHub release, unchanged
./publish-cdn.sh                                     # CDN copy for QR provisioning, unchanged
EVTRACK_RELEASES_AWS_PROFILE=evtrack-jan scripts/publish-release.sh
```

What it does: verifies the APK signature (and refuses the Android debug certificate), checks the
APK's own versionName equals VERSION, stages it as
`evtrack-kiosk-manager-<VERSION>-release.apk`, uploads it to
`releases/evtrack-kiosk-manager/<VERSION>/` with `manifest.json` last, checks size and sha256 in
S3, then registers the manifest with ARCS.

Rules:
- **Versions are immutable.** Re-running with the same bytes resumes safely. Re-running with
  different bytes for the same VERSION is refused - bump VERSION.
- **Every release lands on BETA.** Promote to STABLE in ARCS (Admin > Releases) to roll it out to
  kiosks. Withdrawing a release in ARCS removes it from the catalogue.
- `--dry-run` shows the manifest and the upload plan without touching AWS.
- A path you pass (`scripts/publish-release.sh path/to/app.apk`) is relative to your current
  directory. For an older build (e.g. the 1.1.4 backfill) set `EVTRACK_RELEASES_VERSION=1.1.4`; the
  APK's versionName must still match it.
- If the "already published?" check against S3 fails for any reason other than "not found", the
  script stops before uploading anything. Fix the problem (network, profile) and re-run.
- If registration fails after upload, just re-run: the upload resumes as identical and only the
  registration is retried.

## Self-update contract (to build in the Kiosk Manager)

1. **Fetch**: `ArcsCatalogueClient.channels("evtrack-kiosk-manager")` - same licence, same proof,
   same 401 codes as FrontDesk. Response shape is identical to the FrontDesk catalogue; the single
   file has `role` `distribution`.
2. **Channel**: use `stable` only. BETA builds are for bench devices, installed by hand from a
   link an ARCS user copies on the Downloads page.
3. **Upgrade only**: install only if the catalogue `version` is numerically greater than the
   installed `versionName`, comparing dotted segments as numbers (`1.10.0 > 1.9.9`). Never
   downgrade, even if STABLE is moved back to an older build.
4. **Verify before install**:
   - SHA-256 of the downloaded file equals the catalogue `sha256`.
   - The APK's signing certificate equals the running manager's own certificate:
     ```kotlin
     val pm = context.packageManager
     val flags = PackageManager.GET_SIGNING_CERTIFICATES
     val mine = pm.getPackageInfo(context.packageName, flags).signingInfo.apkContentsSigners
     val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo?.apkContentsSigners
     val sameSigner = theirs != null && mine.map { it.toByteArray().contentHashCode() }.toSet() ==
         theirs.map { it.toByteArray().contentHashCode() }.toSet()
     ```
   - Either mismatch: delete the file, log it, do not install.
5. **Install**: `PackageInstaller` session for your own package (silent as Device Owner, same path
   as FrontDesk installs). The process is killed on replace: handle
   `Intent.ACTION_MY_PACKAGE_REPLACED` to re-arm scheduling and lockdown state.
6. **Cadence**: at boot (after network) and once a day. Respect `Cache-Control: private,
   max-age=300`. Re-fetch the catalogue every time; never store the signed URLs (they expire after
   24 hours).
7. **Order**: check the Kiosk Manager before FrontDesk in the same cycle.
8. **Kill switch**: deactivating the fleet system in ARCS (`system_inactive`) stops all updates.

## Getting the first self-updating build onto existing kiosks

Kiosks on 1.1.4 cannot update themselves. The first build that implements the contract above must
reach them once: bundled in the next OS image, or installed by hand (ADB). From then on, new Kiosk
Manager releases need only `scripts/publish-release.sh` plus a STABLE promotion in ARCS.
