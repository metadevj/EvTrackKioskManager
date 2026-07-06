#!/bin/bash
# =============================================================================
# Prepare a release: draft RELEASE.md entry, commit, and tag
# =============================================================================
#
# Usage:
#   ./release.sh              # Draft release notes, open editor, commit & tag
#   ./release.sh --dry-run    # Show what would be added, don't modify anything
#
# Prerequisites:
#   - On the master branch (releases are cut from master ONLY — no beta releases)
#   - All code changes committed
#   - VERSION bumped (via scripts/bump-version.sh)
#
# What it does:
#   1. Reads version from VERSION file (MAJOR.MINOR.BUILD)
#   2. Collects commits since the last tag (or recent 20 if no tags)
#   3. Prepends a draft entry to RELEASE.md
#   4. Opens your editor to refine the notes
#   5. Commits RELEASE.md and creates an annotated tag
#
# Tag format: v{VERSION}   (e.g. v1.0.2)
#
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VERSION=$(cat VERSION | tr -d '[:space:]')
BRANCH=$(git branch --show-current)
TAG="v${VERSION}"
DATE=$(date +%Y-%m-%d)
RELEASE_FILE="RELEASE.md"
DRY_RUN=false

if [ "$1" == "--dry-run" ]; then
    DRY_RUN=true
fi

# ── Checks ──────────────────────────────────────────────────────────────────

# Releases are cut from master only (production). We develop on dev.
if [ "$BRANCH" != "master" ]; then
    echo "Error: releases must be cut from master (production), not '$BRANCH'."
    echo "  Promote your changes first:  git checkout master && git merge dev"
    exit 1
fi

if [ ! -f "$RELEASE_FILE" ]; then
    echo "Error: $RELEASE_FILE not found"
    exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Error: Tag $TAG already exists"
    echo "  Bump VERSION before running release.sh:"
    echo "    ./scripts/bump-version.sh"
    exit 1
fi

# Check for uncommitted changes (except RELEASE.md itself)
if [ -n "$(git status --porcelain | grep -v RELEASE.md)" ]; then
    echo "Error: Uncommitted changes detected"
    echo "  Commit or stash your changes before releasing"
    echo ""
    git status --short
    exit 1
fi

# ── Collect commits since last tag ──────────────────────────────────────────

LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")

if [ -n "$LAST_TAG" ]; then
    echo "Commits since $LAST_TAG:"
    COMMITS=$(git log "${LAST_TAG}..HEAD" --pretty=format:"- %s" --no-merges)
else
    echo "No previous tags found. Showing recent commits:"
    COMMITS=$(git log --pretty=format:"- %s" --no-merges -20)
fi

echo "$COMMITS"
echo ""

# ── Dry run stops here ─────────────────────────────────────────────────────

if [ "$DRY_RUN" = true ]; then
    echo "--- Dry run ---"
    echo "Would create: $RELEASE_FILE entry for [$VERSION] - $DATE"
    echo "Would create: git tag $TAG"
    echo "  Version:  $VERSION"
    echo "  Branch:   $BRANCH"
    exit 0
fi

# ── Check for existing entry ────────────────────────────────────────────────

if grep -q "## \[${VERSION}\]" "$RELEASE_FILE"; then
    echo "RELEASE.md already has an entry for [$VERSION]."
    echo ""
    read -r -p "Skip to tagging? [y/N] " response
    if [[ "$response" =~ ^[Yy]$ ]]; then
        git tag -a "$TAG" -m "Release $VERSION"
        echo "Tagged: $TAG"
        echo ""
        echo "Push with:"
        echo "  ./push-release.sh"
        exit 0
    else
        echo "Aborted."
        exit 1
    fi
fi

# ── Draft release entry ────────────────────────────────────────────────────

DRAFT=$(cat <<EOF
## [$VERSION] - $DATE

### Changed
$COMMITS

EOF
)

# Prepend draft after the "---" separator
TMPFILE=$(mktemp)
awk -v draft="$DRAFT" '
    /^---$/ && !inserted {
        print
        print ""
        printf "%s", draft
        inserted=1
        next
    }
    { print }
' "$RELEASE_FILE" > "$TMPFILE"
mv "$TMPFILE" "$RELEASE_FILE"

echo "Draft entry added to $RELEASE_FILE"
echo ""

# ── Open editor ─────────────────────────────────────────────────────────────

EDITOR="${EDITOR:-${VISUAL:-vi}}"
echo "Opening $EDITOR to refine release notes..."
echo "  Categorize commits under: ### Added, ### Changed, ### Fixed, ### Removed"
echo "  Remove irrelevant commits (chores, typos, etc.)"
echo ""
read -r -p "Press Enter to open editor (or Ctrl+C to abort)..."
$EDITOR "$RELEASE_FILE"

# ── Confirm ─────────────────────────────────────────────────────────────────

echo ""
echo "--- Release entry for $VERSION ---"
awk "/^## \[${VERSION}\]/{found=1; print; next} /^## \[/{found=0} found{print}" "$RELEASE_FILE"
echo "---"
echo ""
echo "  Tag:     $TAG"
echo "  Branch:  $BRANCH"
echo ""
read -r -p "Commit and tag $TAG? [Y/n] " response
if [[ "$response" =~ ^[Nn]$ ]]; then
    echo "Aborted. RELEASE.md has been modified but not committed."
    exit 1
fi

# ── Commit and tag ──────────────────────────────────────────────────────────

git add "$RELEASE_FILE"
git commit -m "Release $VERSION"
git tag -a "$TAG" -m "Release $VERSION"

echo ""
echo "Done!"
echo "  Committed: Release $VERSION"
echo "  Tagged:    $TAG"
echo ""
echo "Push with:"
echo "  ./push-release.sh"
