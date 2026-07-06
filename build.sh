#!/bin/bash
# =============================================================================
# Build the signed release APK for EvTrack Kiosk Manager.
# =============================================================================
#
# Signs with the SHARED EvTrack release keystore (alias evtrack-release) — the
# same key as the main app (com.evtrack.frontdesk). Signing is driven entirely
# by environment variables; nothing secret lives in this repo.
#
# Usage:
#   ./build.sh                 # build signed release APK
#   ./build.sh --clean         # clean first
#
# The four signing vars are exported by the shared keys.sh:
#   EVTRACK_KEYSTORE_FILE  EVTRACK_KEYSTORE_PASSWORD
#   EVTRACK_KEY_ALIAS      EVTRACK_KEY_PASSWORD
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

KEYS_SH="${EVTRACK_KEYS_SH:-/home/janz/data/development/evtrack/release/keys.sh}"

# Source the shared signing env if the vars aren't already exported.
if [ -z "$EVTRACK_KEYSTORE_FILE" ] && [ -f "$KEYS_SH" ]; then
    echo "Sourcing signing keys from $KEYS_SH"
    # shellcheck disable=SC1090
    source "$KEYS_SH"
fi

CLEAN=""
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN="clean" ;;
    esac
done

VERSION=$(cat VERSION 2>/dev/null | tr -d '[:space:]')
APK_ASSET="evtrack-kiosk-manager-universal-release.apk"

echo ""
echo "========================================"
echo "  EvTrack Kiosk Manager - Build"
echo "  Version: ${VERSION:-unknown}"
echo "========================================"

if [ -z "$EVTRACK_KEYSTORE_FILE" ] || [ -z "$EVTRACK_KEYSTORE_PASSWORD" ] || \
   [ -z "$EVTRACK_KEY_ALIAS" ] || [ -z "$EVTRACK_KEY_PASSWORD" ]; then
    echo "Warning: signing env vars not set (EVTRACK_KEYSTORE_FILE/PASSWORD, EVTRACK_KEY_ALIAS/PASSWORD)."
    echo "         Set EVTRACK_KEYS_SH or source keys.sh first. The release APK will be UNSIGNED."
    read -r -p "Continue without signing? [y/N] " response
    [[ "$response" =~ ^[Yy]$ ]] || exit 1
fi

./gradlew $CLEAN assembleRelease

# Stage the built APK under the EvTrack naming convention
# (<app-slug>-universal-release.apk) so downstream — the OS image embed, the
# CDN mirror, and the GitHub release asset — all use the same filename.
GRADLE_APK="app/build/outputs/apk/release/app-release.apk"
mkdir -p dist
if [ -f "$GRADLE_APK" ]; then
    cp -f "$GRADLE_APK" "dist/$APK_ASSET"
fi

echo ""
echo "APK:"
echo "  $GRADLE_APK"
echo "  dist/$APK_ASSET   (release/CDN name)"

echo ""
echo "Verify signature:  apksigner verify --print-certs dist/$APK_ASSET"
