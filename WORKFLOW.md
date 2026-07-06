# Development Workflow

Daily development and release workflow for the **EvTrack Kiosk Manager**
(`com.evtrack.kioskmanager`) — the Device-Owner app that installs / updates /
recovers the FrontDesk kiosk app and enforces kiosk lockdown.

## Branching

```
dev  →  master
```

| Branch | Purpose |
|--------|---------|
| `dev`    | Development and on-device testing — all work happens here |
| `master` | Production — stable, **released** code |

Develop on `dev`; when it's good, merge into `master` and release from `master`.
There is one release type (a single signed universal APK) — no beta.

```bash
# Release: promote dev to master
git checkout master && git merge dev && git push origin master
```

## Development

```bash
./gradlew assembleDebug      # build debug APK
./gradlew installDebug       # build + install on connected device
./gradlew lintDebug          # lint
```

Commit convention: `feat:` / `fix:` / `chore:` / `docs:` / `refactor:` / `perf:`.

## Versioning

Single source of truth is the root **`VERSION`** file (`MAJOR.MINOR.BUILD`).
`app/build.gradle` reads it: `versionName` = the full string, `versionCode` =
the `BUILD` component (always increments, so it stays monotonic).

```bash
./scripts/bump-version.sh          # increment build number (1.0.2 -> 1.0.3)
./scripts/bump-version.sh minor    # bump minor  (1.0.2 -> 1.1.3)
./scripts/bump-version.sh major    # bump major  (1.0.2 -> 2.0.3)
```

## APK naming convention

The build emits a single **universal** APK (no ABI splits) at
`app/build/outputs/apk/release/app-release.apk`. Following the EvTrack-wide
scheme `<app-slug>-universal-release.apk` (cf. FrontDesk's
`evtrack-front-desk-universal-release.apk`), `build.sh` stages it and
`push-release.sh` publishes it as:

```
evtrack-kiosk-manager-universal-release.apk
```

Same name the CDN uses under `evtrack-kiosk-manager/<version>.<build>/…`, so a
GitHub asset mirrors to the CDN with no rename (needed for QR provisioning), and
the stable permalink works:

```
https://github.com/metadevj/EvTrackKioskManager/releases/latest/download/evtrack-kiosk-manager-universal-release.apk
```

## Releasing (from `master`)

```bash
git checkout master && git merge dev   # 0. promote dev
./scripts/bump-version.sh              # 1. bump version
git add VERSION && git commit -m "Bump version to $(cat VERSION)"
./build.sh --clean                     # 2. build the signed release APK
./release.sh                           # 3. draft RELEASE.md, commit, tag v<VERSION>
./push-release.sh                      # 4. push branch + tag, upload the APK
```

`release.sh` refuses to run off any branch other than `master`. Pushing the `v*`
tag triggers `.github/workflows/release.yaml`, which creates the GitHub Release
from the matching `RELEASE.md` section; `push-release.sh` attaches the APK.

### Signing

Signed with the **shared** `evtrack-release` keystore (same key as FrontDesk),
driven entirely by environment variables — nothing secret lives in-tree:

```
EVTRACK_KEYSTORE_FILE   EVTRACK_KEYSTORE_PASSWORD
EVTRACK_KEY_ALIAS       EVTRACK_KEY_PASSWORD
```

`build.sh` sources these from `keys.sh` (override path via `EVTRACK_KEYS_SH`).

## Publishing to the CDN

Separately from the GitHub release, the same signed APK is published to the
EvTrack APK CDN (`downloads.evtrack.com/public/apk`) — what QR provisioning and
fresh-device bootstrap pull from. Run it as its own step after a release:

```bash
./publish-cdn.sh              # publish dist/<apk> to the CDN
./publish-cdn.sh --dry-run    # stage + show the rclone plan, upload nothing
```

It follows the live CDN convention — **version sorted by folder, constant
filename**:

```
evtrack-kiosk-manager/<version>.<build>/evtrack-kiosk-manager-universal-release.apk
evtrack-kiosk-manager/latest/evtrack-kiosk-manager-universal-release.json
    { name, version, build, sha256 }
```

version/build come from `VERSION` (1.0.2 → version `1.0`, build `2`). Upload is
`rclone copy` (no deletes — old versions are kept). Requires `S3/rclone.conf`
with an `[evtrack-downloads]` R2 remote — copy `S3/rclone.conf.example` and fill
in the Cloudflare R2 credentials (the real config is gitignored).

## Quick reference

| Task | Command |
|------|---------|
| Debug build | `./gradlew assembleDebug` |
| Install debug | `./gradlew installDebug` |
| Release build | `./build.sh --clean` |
| Bump version | `./scripts/bump-version.sh` |
| Release | `./release.sh` |
| Release dry run | `./release.sh --dry-run` |
| Push release | `./push-release.sh` |
