---
name: ship
description: Use when the user asks to run the whole Kiosk Manager pipeline in one go - "ship this", "ship it", "run the pipeline", "release this end to end", "/ship [build|minor|major]" - taking the current work from feature branch through beta, master, the tagged release and the ARCS publish. For a single phase, use pr-beta, pr-main, cut-release or publish-apks directly.
---

# Ship: the whole pipeline in one command

## Purpose

Drive the full delivery pipeline with ONE human approval: **land the feature branch on `beta`,
promote to `master`, cut and publish the release (GitHub + ARCS)**, then report it all in one
summary.

This skill is an orchestrator. Every mechanic lives in the component skills and their rules apply
unchanged unless this file says otherwise:

| Phase | Skill | What it does |
|---|---|---|
| 1 Land | pr-beta | from `dev`/`feature`: sync beta in, gate, PR, merge commit, verify |
| 2 Promote | pr-main | show what promotes, gate the merged result, merge to master |
| 3 Release | cut-release | bump, signed build from AWS keys, RELEASE.md, tag, GitHub release, ARCS publish, back-merge master -> beta -> dev |

An optional argument (`build` | `minor` | `major`) is passed to cut-release's bump step.

## Phase 0 - Assess, then get the single approval

Gather, without changing anything:

- Current branch and working tree state - `dev` and feature branches are both valid starting
  points. If dirty, draft the commit message NOW: it goes in this summary, because there is no later
  pause to propose it in.
- What would land: `git log origin/beta..HEAD --oneline` (or "nothing to land" when already on
  `beta`/`master`).
- What would promote: `git log origin/master..origin/beta --oneline`.
- The inferred bump (per cut-release Step 1), the resulting version, and the tag `v<VERSION>`.
- That the publish lands on the ARCS **beta** channel and will NOT reach kiosks until someone
  promotes it to stable in ARCS admin.

Present ONE summary and STOP for an explicit yes.

**That yes is the pipeline's only approval.** It stands in for cut-release's Step 4 notes pause:
the notes are still drafted to the same standard, but they ship without a second look and appear
verbatim in the final report. It also covers the dirty-tree commit whose message was shown. It does
NOT override any gate: build, lint, aapt2 versionCode, signature and registration checks all still
decide pass or fail on their own evidence.

## Phases 1-3 - Run

Run the component skills in order, each per its own SKILL.md. Between phases, state a one-line
progress note. Skip Phase 1 when already on `beta`/`master` with nothing to land; skip Phase 2 when
`master` already contains `beta`.

## Skip and resume

Every phase self-detects completion, so re-invoking ship after a failure resumes rather than redoes:

| Phase | Already done when | Action on re-run |
|---|---|---|
| Land | branch merged into origin/beta (`git merge-base --is-ancestor`) | skip |
| Promote | `git merge-base --is-ancestor origin/beta origin/master` | skip |
| Release | tag exists AND the GitHub release has the APK | skip to unfinished sub-steps; the ARCS publish resumes safely by design |

Never re-tag and never re-publish different bytes. A resumed run still starts at Phase 0, but the
summary says what is already done and the approval covers only the rest.

## Failure policy

- Mechanical, in-scope fixes are made, committed and reported (a build-file breakage the gate
  exposes, a missing PATH export).
- STOP for: new red that is not inherited from the base, semantic merge conflicts, a signature that
  is not the shared `evtrack-release` key, an aapt2 versionCode mismatch, anything a component skill
  says to stop for, and genuine scope changes.
- Report exactly which phase and step stopped, and why. The user re-invokes ship to resume.

## Final report

One consolidated summary:

- PR number and merge commit (or "already landed"), and what promoted to master
- Version, tag, GitHub release URL
- ARCS registration result and the release notes as shipped
- The APK's sha256 and signing certificate
- The two things that stay manual by design: **promoting the release from beta to stable in ARCS
  admin**, and **testing the build on a bench kiosk first** - the Manager's self-update is
  upgrade-only and silent once promoted, so a bad stable build is in front of every device.

## Red Flags - STOP

- "Skip the Phase 0 summary, they said ship it" - "ship it" invokes the pipeline; the summary and
  its yes ARE the contract.
- "The single approval means I can merge red" - the approval replaces pauses, never gates.
- "Re-run from scratch to be safe" - resuming is the safe path; from-scratch re-runs re-tag.
- "Draft weaker release notes since nobody reviews them" - unreviewed is exactly why they must meet
  the reviewed standard; they go on the public release.
- "Promote to stable as part of shipping" - never. Beta first, bench test, then a human promotes.
