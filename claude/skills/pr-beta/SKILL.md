---
name: pr-beta
description: Use when the user asks to land, PR, or merge the current feature branch into beta - "PR this to beta", "land this", "ship this to beta", "merge this into beta", "/pr-beta". Only ever runs FROM a feature branch; promoting beta to master is pr-main.
---

# PR to beta

## Purpose

Land the current feature branch on `beta` through a PR: **sync the branch with the latest base
first, re-gate locally, merge with a merge commit, verify it landed.**

## The branch model

`feature -> beta -> master`, the same shape as EvTrackFrontDesk:

| Branch | Role |
|---|---|
| `feature/*` | where work happens |
| `beta` | integration and bench testing - this skill's destination |
| `master` | production; releases are cut here (`release.sh` refuses any other branch) |

The ARCS channel is a separate thing from the branch: every publish lands on the **beta** channel
and a human promotes it to **stable** in ARCS admin.

Core principle: **no branch protection, no PR CI.** The only workflow (`.github/workflows/release.yaml`)
fires on version tags. Every gate below is local and mandatory.

## Preconditions - CHECK THE BRANCH FIRST (abort if any fails)

```bash
BRANCH=$(git branch --show-current)
case "$BRANCH" in
  beta|master|main) echo "ABORT: pr-beta runs from a FEATURE branch, not $BRANCH"; exit 1 ;;
  "")               echo "ABORT: detached HEAD"; exit 1 ;;
esac
echo "feature branch: $BRANCH"
```

- **Current branch must NOT be `beta`, `master` (or `main`), and must not be detached.** This is
  the first thing to check, before reading anything else about the change. On `beta` there is
  nothing to land and a PR would be beta-into-beta; on `master` it would try to drag production
  history backwards into the integration branch. If the user asks for pr-beta while standing on
  either, say which branch they are on and stop - the thing they want is probably pr-main (promote)
  or cut-release.
- `gh auth status` shows a logged-in account.
- You know which issues the branch fixes; they go in the PR body.

## Step 1 - Settle the working tree

`git status --short`. If dirty: propose a commit message, wait for a yes, then commit. Invoking
this skill authorizes the commit - never invent the message silently.

## Step 2 - Sync latest beta INTO the branch (always)

```bash
git fetch origin beta
git merge origin/beta
```

Run it even when the base has not moved - it no-ops. Conflict rules:

- `VERSION`: always take the incoming side. Bumps belong to the release flow, never to a feature
  branch.
- Mechanical conflicts: resolve in place.
- Semantic conflicts: STOP and show the user both sides.

## Step 3 - The gate (local, mandatory)

```bash
./build.sh --clean          # pins JDK 17 and builds the release variant
./gradlew lintRelease
```

This repo has **no unit tests** (no `testImplementation`, no `app/src/test`), so the gate is a
clean release build plus lint - say so in the PR body rather than implying tests ran. If the build
asks "Continue without signing?" the signing fetch failed: answer no. A gate build does not need
signing, but a silent fallback hides a broken AWS fetch you will hit at release time.

Judge lint by `app/build/reports/lint-results-release.xml`, not by the BUILD SUCCESSFUL line. No
`severity="Error"` may point at a file this branch touched. Anything NEW is red: fix or stop.

Why gate after the sync: the post-merge tree is new and has never been built by anyone.

## Step 4 - Push and create the PR

```bash
git push -u origin <feature-branch>
gh pr create --base beta --title "<descriptive title>" --body "<body>"
```

Body: issues fixed (`Fixes metadevj/EvTrackIssues#NNN`), a short summary per change, the gate
evidence (build result, lint result, and the explicit note that this repo has no unit tests), and
the footer:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Confirm `git log --oneline origin/beta..HEAD` shows exactly the commits you expect.

## Step 5 - Merge, then verify

```bash
gh pr merge --merge                                  # merge commit, never squash
gh pr view <pr-number> --json state,mergeCommit      # MERGED, non-null mergeCommit
git checkout beta && git pull && git log --oneline -3
```

Report the PR number/URL, the merge commit, and that the release itself is a separate step:
pr-main, then cut-release.

## Red Flags - STOP

- "CI will catch it" - there is no PR CI; `release.yaml` runs on tags only.
- "It was tested before the sync" - the post-sync tree is a new, untested merge.
- "Release straight from beta" - `release.sh` refuses any branch but `master`; use pr-main.
- "I'm on beta, just merge the feature in locally" - the guard exists because that skips the sync,
  the gate and the record of what landed. Check out the feature branch and run this properly.
- "Bump VERSION while I'm here" - bumps happen in the release flow.
- "Squash to keep history tidy" - this line lands merge commits.
