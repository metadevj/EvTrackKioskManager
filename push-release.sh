#!/bin/bash
# =============================================================================
# Push release commit and tag to remote, then upload the APK
# =============================================================================
#
# Usage:
#   ./push-release.sh         # Push branch, tag, and upload the release APK
#
# Reads the version from VERSION, determines the tag (vX.Y.Z), verifies it
# exists locally, pushes both branch and tag to origin, waits for the
# GitHub Release (created by .github/workflows/release.yaml) and uploads the
# locally-built signed APK.
#
# APK naming convention (EvTrack-wide):  <app-slug>-universal-release.apk
#   Gradle emits app/build/outputs/apk/release/app-release.apk (a single,
#   universal APK — no ABI splits). It is uploaded as:
#       evtrack-kiosk-manager-universal-release.apk
#   This is the SAME name the CDN uses under
#       evtrack-kiosk-manager/<version>.<build>/evtrack-kiosk-manager-universal-release.apk
#   so a GH asset can be mirrored to the CDN with no rename, and the stable
#   permalink works:
#       .../releases/latest/download/evtrack-kiosk-manager-universal-release.apk
#
# Requires: gh CLI (https://cli.github.com/)
#
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VERSION=$(cat VERSION | tr -d '[:space:]')
BRANCH=$(git branch --show-current)
TAG="v${VERSION}"

# APK naming convention (see header)
APP_SLUG="evtrack-kiosk-manager"
APK_ASSET="${APP_SLUG}-universal-release.apk"
GRADLE_APK="app/build/outputs/apk/release/app-release.apk"
DIST_DIR="dist"

# Releases are pushed from master only.
if [ "$BRANCH" != "master" ]; then
    echo "Error: releases are pushed from master only, not '$BRANCH'."
    exit 1
fi

# Verify tag exists locally
if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Error: Tag $TAG does not exist locally"
    echo "  Run ./release.sh first to create the tag"
    exit 1
fi

# Verify the built APK exists, then stage it under the convention name
if [ ! -f "$GRADLE_APK" ]; then
    echo "Error: $GRADLE_APK not found"
    echo "  Build first:  ./build.sh --clean"
    exit 1
fi

mkdir -p "$DIST_DIR"
STAGED_APK="$DIST_DIR/$APK_ASSET"
cp -f "$GRADLE_APK" "$STAGED_APK"

echo "Pushing release $VERSION"
echo "  Branch: $BRANCH"
echo "  Tag:    $TAG"
echo "  APK:    $STAGED_APK"
echo "          (from $GRADLE_APK)"
echo ""

# Show the signing cert so we don't publish an unsigned/wrong-key APK
if command -v apksigner &>/dev/null; then
    echo "Signature:"
    apksigner verify --print-certs "$STAGED_APK" 2>/dev/null | grep -i "SHA-256" | head -1 || \
        echo "  (could not read certificate — is the APK signed?)"
    echo ""
fi

read -r -p "Push to origin and upload? [Y/n] " response
if [[ "$response" =~ ^[Nn]$ ]]; then
    echo "Aborted."
    exit 1
fi

git push origin "$BRANCH"
git push origin "$TAG"

echo ""
echo "Pushed. GitHub Actions will create the release."

if ! command -v gh &>/dev/null; then
    echo ""
    echo "gh CLI not found. Install it to upload the APK:"
    echo "  https://cli.github.com/"
    echo ""
    echo "Then upload manually:"
    echo "  gh release upload $TAG $STAGED_APK"
    exit 0
fi

echo ""
echo "Waiting for GitHub Release to be created..."
# Poll for the release (GH Actions takes a few seconds)
for i in $(seq 1 30); do
    if gh release view "$TAG" &>/dev/null; then
        echo "Release found. Uploading APK..."
        gh release upload "$TAG" "$STAGED_APK" --clobber
        echo ""
        echo "Done! Uploaded $APK_ASSET to release $TAG"
        echo "  Permalink: https://github.com/metadevj/EvTrackKioskManager/releases/latest/download/$APK_ASSET"
        gh release view "$TAG" --web 2>/dev/null || true
        exit 0
    fi
    sleep 2
done

echo ""
echo "Release not found yet. Upload the APK manually once it's created:"
echo "  gh release upload $TAG $STAGED_APK"
