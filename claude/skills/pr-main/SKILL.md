---
name: pr-main
description: Use when the user asks to promote beta to master, or to prepare a release - "promote beta", "merge beta into master", "PR to master", "prepare the release", "/pr-main". Runs ONLY from the beta branch and merges into master; releases are cut from master afterwards with cut-release.
---

# Promote beta to master

## Purpose

Move what is on `beta` onto `master` so a release can be cut: **check the branch, see exactly what
would promote, gate the merged result, merge, verify** - and nothing else. No bump, no tag, no
artefacts. Those belong to cut-release.

## Preconditions - CHECK THE BRANCH FIRST (abort if any fails)

```bash
BRANCH=$(git branch --show-current)
[ "$BRANCH" = "beta" ] || { echo "ABORT: pr-main runs FROM beta, not ${BRANCH:-detached HEAD}"; exit 1; }
git fetch origin
git status --short      # must be empty
```

- **The current branch MUST be `beta`.** Promotion means "what we have been bench-testing on beta
  becomes production", so it starts there and nowhere else:
  - on a **feature branch**: the work has not been integrated or gated on beta yet - run pr-beta
    first, then come back;
  - on **master**: there is nothing to promote FROM; the user probably wants cut-release;
  - **detached HEAD**: stop.
- Working tree clean. This flow never commits source changes.
- `gh auth status` logged in.

## Step 1 - See exactly what would promote

```bash
git log --oneline origin/master..origin/beta
git diff --stat origin/master...origin/beta
```

Show the user this list first. If empty, STOP: `master` already contains `beta` and the next step
is cut-release, not this.

## Step 2 - Gate the merged result

```bash
git checkout master && git pull
git merge --no-commit --no-ff origin/beta     # inspect before committing
./build.sh --clean
./gradlew lintRelease
```

This repo has **no unit tests** (no `testImplementation`, no `app/src/test`), so the gate is a clean
release build plus lint - state that rather than implying tests ran. Judge lint from
`app/build/reports/lint-results-release.xml`; new `severity="Error"` entries touching promoted files
are red: stop.

Conflicts are unusual here (master is normally an ancestor of beta). If one appears, STOP and show
both sides - never guess on the production branch.

If the gate fails, abort the merge (`git merge --abort`) and return the user to `beta`; master must
never be left mid-merge.

## Step 3 - Merge and verify

```bash
git commit -m "Merge beta into master for <version or purpose>"
git push origin master
git merge-base --is-ancestor origin/beta origin/master && echo "beta is contained in master"
git log --oneline -3
```

A PR is optional (no branch protection, no PR CI) and a direct merge commit is the house style,
matching "Merge dev into master for 1.1.4" in the history. Use a PR only when the user wants the
diff reviewed in GitHub:

```bash
gh pr create --base master --head beta --title "Promote beta to master" --body "<what lands>"
gh pr merge --merge
```

## Step 4 - Hand over

Report what promoted (the Step 1 list), the merge commit, and that the next step is cut-release -
which does the bump, signed build, notes, tag, GitHub release and ARCS publish.

Do NOT bump the version here. `bump-version.sh` runs inside cut-release, after the promotion, so a
version is only minted for a release that actually happens.

## Red Flags - STOP

- "Run it from master, it's the same merge" - the guard is the point: promotion starts on beta, so
  what you are promoting is what was bench-tested.
- "Promote straight from a feature branch" - that skips beta entirely; use pr-beta first.
- "Bump the version while promoting" - cut-release owns the bump.
- "Squash beta into master" - the history is merge commits; keep it.
- "Force push master to match beta" - never. Merge.
- "Nothing to promote, so merge anyway" - an empty promotion is a no-op commit; go to cut-release.
