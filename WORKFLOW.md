# Development Workflow

Daily development and release workflow for the **EvTrack Kiosk Manager**
(`com.evtrack.kioskmanager`) — the Device-Owner app that installs / updates /
recovers the FrontDesk kiosk app and enforces kiosk lockdown.

## Branching

```
feature/*  →  dev  →  beta  →  master
```

| Branch | Purpose |
|--------|---------|
| `feature/*` | Larger pieces of work, cut from `dev` |
| `dev`       | Day-to-day development and on-device testing |
| `beta`      | Integration and bench testing — what gets released to the ARCS **beta** channel |
| `master`    | Production — stable, **released** code |

Work on `dev` (or a feature branch off it). When it is good, land it on `beta`,
bench-test the release, then promote `beta` to `master` and release from there.
`release.sh` refuses to run on any branch but `master`.

There is one release type: a single signed universal APK. "Beta" is **not** a
separate build — it is the same release left on the ARCS beta channel rather
than promoted to stable.

```bash
git checkout beta   && git merge dev  && git push origin beta     # land work for bench testing
git checkout master && git merge beta && git push origin master   # promote for release
```

After a release, the bump and `RELEASE.md` are minted on `master`, so merge back
down the line or `beta` and `dev` fall behind every release:

```bash
git checkout beta && git merge master && git push origin beta
git checkout dev  && git merge beta   && git push origin dev
```

### Claude Code skills

The steps above are automated, with their gates and guards, in `claude/skills/`:

| Skill | Runs from | Does |
|-------|-----------|------|
| `pr-beta` | `dev` or `feature/*` | sync `beta` in, gate, PR, merge, verify |
| `pr-main` | `beta` only | show what promotes, gate, merge to `master` |
| `cut-release` | `master` | bump, signed build, notes, tag, GitHub release, ARCS publish, back-merge |
| `publish-apks` | — | APK from the GitHub release → `evtrack-releases` → register with ARCS |
| `ship` | — | all three phases on one approval |

`claude/` is the shared, reviewable copy and is committed. `/.claude/` is
gitignored for local context; `.claude/skills` is a symlink to `claude/skills`
so Claude Code still discovers them.

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
`MAJOR*10000 + MINOR*100 + BUILD`, so the code rises with the version itself:

| VERSION | versionCode |
|---------|-------------|
| 1.2.5   | 10205 |
| 1.3.1   | 10301 |
| 2.0.0   | 20000 |

Keep **MINOR and BUILD each below 100**, or the arithmetic collides (1.2.100
and 1.3.0 would both be 10300). Any version you pick must be numerically
greater than the last released one — Android refuses a lower `versionCode` as
a downgrade, and the Manager's self-update is upgrade-only.

> Before 1.3.1 the code was the BUILD component alone, so it only stayed
> monotonic while that one number never went down (1.2.5 was code 5). Codes
> under the new scheme start at 10301, well clear of every old one.

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
git checkout master && git merge beta  # 0. promote beta (which already carries dev)
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

## Publishing to ARCS

The same signed APK is also registered in ARCS, which is where kiosks look for Kiosk Manager
updates. Run it after `publish-cdn.sh`:

```bash
EVTRACK_RELEASES_AWS_PROFILE=evtrack-jan scripts/publish-release.sh             # upload + register
EVTRACK_RELEASES_AWS_PROFILE=evtrack-jan scripts/publish-release.sh --dry-run   # show the plan only
```

A new release lands on **BETA**. Kiosks only self-update from **STABLE**, so nothing rolls out until
an ARCS admin promotes the release (Admin > Releases). The branch a release was cut from does not
decide the channel — every publish lands on beta, and promotion is a deliberate human step after
the build has run on a bench kiosk.

Self-update is **manual**: Settings (gear) → Kiosk Manager update → Main or Beta. There is no
scheduled or boot-time check anywhere in the app. Full details, including the self-update
contract: [docs/ARCS-RELEASES.md](docs/ARCS-RELEASES.md).

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
