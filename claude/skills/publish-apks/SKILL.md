---
name: publish-apks
description: Use when the user asks to publish the Kiosk Manager APK, publish to ARCS, or make a version available - "publish the APK", "publish 1.2.5", "/publish-apks [version]" - or as the final step of cut-release after the GitHub release is verified.
---

# Publish the Kiosk Manager to ARCS

## Purpose

Make a released Kiosk Manager version available on ARCS: **take the APK from the GitHub release,
upload it to the `evtrack-releases` bucket, and register the version with ARCS** under product
`evtrack-kiosk-manager`.

`scripts/publish-release.sh` does the work: signature check, versionName check, manifest with
sha256/size, immutable-version precheck, files-first-manifest-last upload, post-upload verification,
then ARCS registration (token from Secrets Manager `evtrack/arcs/releases-api-token`, eu-central-1).
The skill's job is correct input and honest verification around it.

## Who consumes this

Two different readers, and it matters:

- **Kiosks**, through the Manager's own self-update (Settings -> Kiosk Manager update). That reads
  the catalogue for `evtrack-kiosk-manager` with the fleet licence, and it is **upgrade-only** -
  publishing a version with a lower last component than what is installed is invisible to it.
- **People**, on the ARCS downloads page.

Unlike the OS product, kiosks DO fetch this one, so a bad publish reaches devices once promoted.

## Preconditions (abort if any fails)

- Version resolved: the skill argument if given, else the `VERSION` file.
- The GitHub release `v<version>` exists and has the APK asset. The GitHub release is the canonical
  source - never publish a local build directly, so what customers get is what was tagged.
- `aws sts get-caller-identity --profile "${EVTRACK_RELEASES_AWS_PROFILE:-evtrack-releases}"` works.
- `apksigner` and `aapt2` on PATH. The script checks for them and aborts with
  "apksigner not on PATH (Android build-tools)" - it does NOT search `ANDROID_HOME` itself:
  ```bash
  export PATH="$HOME/Android/Sdk/build-tools/37.0.0:$PATH"
  ```

## Step 1 - Take the APK from the GitHub release

```bash
STAGE=<scratchpad>/publish/<version>
mkdir -p "$STAGE"
gh release download "v<version>" --pattern '*.apk' --dir "$STAGE"
```

Exactly one APK: `evtrack-kiosk-manager-universal-release.apk`. Verify it is the version being
published before going further:

```bash
aapt2 dump badging "$STAGE"/*.apk | grep '^package'      # versionName == <version>, versionCode == last component
apksigner verify --print-certs "$STAGE"/*.apk | grep SHA-256
# 29b5f0b45d9a3d67a15460f9f94444f78288fc7c211bbc95c01391fd8df2a8ab - the shared evtrack-release key
```

A different certificate means kiosks could not install it over the running Manager even if
published. Abort rather than publish it.

## Step 2 - Dry-run, then publish

```bash
EVTRACK_RELEASES_AWS_PROFILE=evtrack-releases scripts/publish-release.sh "$STAGE"/*.apk --dry-run
EVTRACK_RELEASES_AWS_PROFILE=evtrack-releases scripts/publish-release.sh "$STAGE"/*.apk
```

Read the dry-run manifest first: one file, `role: distribution`, the right version, and
`channelHint: "beta"` (hardcoded - every release lands on beta).

For a backfill of an older build, `EVTRACK_RELEASES_VERSION=<version>` overrides, and the APK's own
versionName must still match it.

## Step 3 - Verify and report

```bash
aws s3 ls "s3://evtrack-releases/releases/evtrack-kiosk-manager/<version>/" --profile evtrack-releases
```

Expect the APK and `manifest.json`. Report: product, version, channel, file name, sha256, and
whether ARCS registration succeeded.

**Then say plainly what has NOT happened:** the release is on BETA. Kiosks self-update from STABLE
only, so nothing reaches a device until someone promotes it in ARCS admin (Admin -> Releases). That
promotion is deliberate and manual, and is not part of this skill.

## Failure modes

| Symptom | Meaning | Action |
|---|---|---|
| "already published with DIFFERENT content" | version exists with other bytes | Never overwrite: versions are immutable. Bump and cut a new release. |
| "already published with identical content - resuming" | safe re-run | Fine; registration still runs. |
| uploaded but registration failed | S3 has it, ARCS does not | Re-run the same publish; the precheck resumes and retries only the registration. |
| "apksigner not on PATH" | build-tools missing from PATH | Export the build-tools dir; do not skip the signature check. |
| versionName mismatch | the APK is not the version being published | Abort and investigate the release assets. |
| debug certificate refused | a debug build reached the release | Abort; rebuild signed from AWS keys. |

## Red Flags - STOP

- "Publish from app/build/outputs, it's faster" - local outputs may be stale; the GitHub release is
  the source of truth.
- "Overwrite the S3 version, it's mostly the same" - versions are immutable; bump.
- "Skip the dry-run" - the manifest is the contract ARCS registers; look at it.
- "Promote it to stable so the kiosks get it" - a separate human action, after someone has run the
  build on a bench device.
- "Register manually later" - an uploaded, unregistered version is invisible; finish the run.
