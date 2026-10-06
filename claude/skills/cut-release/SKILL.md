---
name: cut-release
description: Use when the user asks to cut, tag, or publish a Kiosk Manager release - "cut a release", "release this", "tag it", "/cut-release" - after pr-main has promoted beta to master. An argument of build, minor, or major overrides the inferred version bump.
---

# Cut a Kiosk Manager release

## Purpose

Turn the current state of `master` into a tagged GitHub Release with a signed APK, then register
it with ARCS: **infer the bump, build signed from AWS-held keys, draft the notes, pause once for
approval, tag, push, verify, publish.**

The flow wraps the repo's own scripts (`scripts/bump-version.sh`, `./build.sh`, `./release.sh`,
`./push-release.sh`, `scripts/publish-release.sh`) and answers their prompts, so their checks still
run.

## The versionCode trap - read before choosing a version

`app/build.gradle` derives `versionCode` from the **last component** of VERSION
(`versionName.tokenize('.').last()`). 1.2.5 is versionCode 5.

So a "tidy" version like 1.3.1 after 1.2.5 is versionCode **1**: Android refuses the install as a
downgrade, and the Manager's own self-updater would offer it (1.3.1 > 1.2.5 numerically) and then
fail. `bump-version.sh` exists to prevent this - it always increments the build component, so minor
from 1.2.5 gives **1.3.6**, not 1.3.1.

Never hand-edit VERSION to a lower last component. If the user asks for one, say what it would do
and offer the next legal number.

## Signing comes from AWS, nowhere else

Secret `evtrack/frontdesk/android-signing`, region `eu-central-1` - shared with FrontDesk, because
the Manager is signed with the SAME `evtrack-release` key (cert SHA-256 `29b5f0b4…`). That shared
identity is load-bearing: it is what lets a new Manager replace the running one, and what the OS
image's `presigned`/`preprocessed` import depends on.

```bash
eval "$(./scripts/fetch-signing-env.sh)"     # writes the keystore to a private runtime dir
./scripts/fetch-signing-env.sh --clean       # right after the build, always
```

Never print the exported values; never write the keystore or passwords into the repo or a shell
profile. If the fetch fails, ABORT - if `build.sh` asks "Continue without signing?", answer no. An
unsigned Manager cannot update any kiosk and cannot go in an OS image.

Note `RELEASING.md` in EvTrackFrontDesk names `evtrack/android/signing`; that secret is not
readable. The script's `evtrack/frontdesk/android-signing` is the live one.

## Preconditions (abort if any fails)

- Current branch is `master`, working tree clean, in sync with origin (`release.sh` enforces the
  branch; check the rest yourself).
- Everything intended is already promoted (pr-main).
- `gh auth status` logged in.
- `aws sts get-caller-identity --profile "${EVTRACK_RELEASES_AWS_PROFILE:-evtrack-releases}"` works.
- `apksigner` and `aapt2` on PATH - `publish-release.sh` needs them and does NOT find them itself:
  `export PATH="$HOME/Android/Sdk/build-tools/37.0.0:$PATH"`.

## Step 1 - Infer the bump

From `git log $(git describe --tags --abbrev=0)..HEAD --oneline`:

- any `feat:` commit: propose `minor`
- only fixes, docs, chores: propose `build`
- `major` is NEVER inferred
- a skill argument overrides the inference

## Step 2 - Bump and build

```bash
./scripts/bump-version.sh <level>        # VERSION only; the commit comes after approval
cat VERSION
eval "$(./scripts/fetch-signing-env.sh)" && ./build.sh --clean
./scripts/fetch-signing-env.sh --clean
```

Build AFTER the bump so the APK embeds the new versionCode - `push-release.sh` verifies that with
aapt2 and rejects stale APKs. Expect ONE signed universal APK, staged as
`dist/evtrack-kiosk-manager-universal-release.apk`.

Confirm before going on:

```bash
apksigner verify --print-certs dist/evtrack-kiosk-manager-universal-release.apk | grep SHA-256
# must be 29b5f0b45d9a3d67a15460f9f94444f78288fc7c211bbc95c01391fd8df2a8ab
```

## Step 3 - Draft the release notes

Write the entry into `RELEASE.md`, newest first:

```markdown
## [<VERSION>] - <YYYY-MM-DD>

### Added
### Changed
### Fixed
```

Categorise the commits since the last tag; drop chores, version bumps and merge noise. Write what
changed for the reader, not raw commit subjects. Omit empty sections.

## Step 4 - The approval pause (the only one)

Show the drafted notes, the new version and the tag (`v<VERSION>` - no branch suffix on this
repo). STOP for an explicit yes. Nothing is committed, tagged or pushed before it.

## Step 5 - Commit and tag

```bash
git add VERSION && git commit -m "Bump version to $(cat VERSION)"
git add RELEASE.md && git commit -m "Release $(cat VERSION)"
printf '\nY\n' | EDITOR=true ./release.sh     # entry exists, so it skips to tagging
git show "v$(cat VERSION):RELEASE.md" | grep "\[$(cat VERSION)\]"
```

`release.sh` opens `$EDITOR` to refine notes and then asks to commit and tag. The notes are already
written and approved, so a no-op editor is correct - but ONLY because Step 4 happened. The final
grep proves the notes are inside the tagged commit, which is what the GitHub workflow publishes.

Never delete or move a PUSHED tag. A local unpushed tag that missed the notes may be re-created.

## Step 6 - Push and upload

```bash
printf 'Y\n' | ./push-release.sh
```

It verifies the APK versionCode against VERSION, pushes branch and tag, waits for the
tag-triggered GitHub Release and uploads the APK.

## Step 7 - Verify

```bash
gh release view "v$(cat VERSION)" --json url,assets \
  --jq '{url, apks: [.assets[].name | select(endswith(".apk"))]}'
```

Pass: the release exists and lists the APK. If the upload poll timed out, retry
`gh release upload` once the release appears; never re-tag to retrigger.

## Step 8 - Publish to ARCS

Run the publish-apks flow. The release is not finished until ARCS registration succeeds; re-running
resumes safely.

## Step 9 - Back-merge

```bash
git checkout beta && git merge master && git push origin beta
```

`RELEASE.md` and the bump are minted on `master`; without this, `beta` drifts behind every release.

## Red Flags - STOP

- "Tag exists, delete and re-tag" - a published tag is immutable; bump instead.
- "Continue without signing, fix it later" - an unsigned Manager is useless; abort.
- "Use 1.3.1, it reads better" - that is versionCode 1 after 5; see the versionCode trap.
- "Skip the notes approval, it's urgent" - it is the release's only human check.
- "Release from beta" - `release.sh` refuses; promote with pr-main first.
- "Promote it to stable while publishing" - publishing lands on BETA by design; promoting is a
  separate human action in ARCS admin.

## Quick reference

| Step | Command | Pass condition |
|---|---|---|
| Bump | `./scripts/bump-version.sh <level>` | VERSION updated, last component increased |
| Build | `eval "$(./scripts/fetch-signing-env.sh)" && ./build.sh --clean` | 1 signed APK, cert `29b5f0b4…`; then `--clean` |
| Notes | edit `RELEASE.md`, show user | explicit yes |
| Tag | commit VERSION + RELEASE.md; `./release.sh` | tag exists AND contains the notes entry |
| Push | `./push-release.sh` | branch+tag pushed, APK uploaded |
| Verify | `gh release view --json url,assets` | release URL with the APK |
| ARCS | publish-apks flow | S3 verified + registration succeeded |
| Back-merge | `git checkout beta && git merge master` | beta carries RELEASE.md |
