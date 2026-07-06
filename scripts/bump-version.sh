#!/bin/bash
# =============================================================================
# Bump version number (MAJOR.MINOR.BUILD)
# =============================================================================
#
# Usage:
#   ./scripts/bump-version.sh           # Increment build number (default)
#   ./scripts/bump-version.sh build     # Increment build number
#   ./scripts/bump-version.sh minor     # 1.0.2 -> 1.1.3
#   ./scripts/bump-version.sh major     # 1.0.2 -> 2.0.3
#
# Updates the root VERSION file (single source of truth). app/build.gradle
# reads it: versionName = the full string, versionCode = the BUILD component.
# The build number always increments on every bump, so versionCode stays
# monotonic across releases.
#
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
VERSION_FILE="$ROOT_DIR/VERSION"

if [ ! -f "$VERSION_FILE" ]; then
    echo "Error: $VERSION_FILE not found"
    exit 1
fi

CURRENT=$(cat "$VERSION_FILE" | tr -d '[:space:]')
IFS='.' read -ra PARTS <<< "$CURRENT"
MAJOR=${PARTS[0]:-0}
MINOR=${PARTS[1]:-0}
BUILD=${PARTS[2]:-0}

echo "Current version: $CURRENT"

case "$1" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        BUILD=$((BUILD + 1))
        ;;
    minor)
        MINOR=$((MINOR + 1))
        BUILD=$((BUILD + 1))
        ;;
    build|"")
        BUILD=$((BUILD + 1))
        ;;
    *)
        echo "Usage: $0 [build|minor|major]"
        exit 1
        ;;
esac

NEW="${MAJOR}.${MINOR}.${BUILD}"

echo "$NEW" > "$VERSION_FILE"
echo "New version:     $NEW"
echo ""
echo "Next steps:"
echo "  1. Build:    ./build.sh --clean"
echo "  2. Commit:   git add VERSION && git commit -m \"Bump version to $NEW\""
echo "  3. Release:  ./release.sh"
