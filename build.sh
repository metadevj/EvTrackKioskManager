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
# The four signing vars come from AWS Secrets Manager, fetched per build:
#   eval "$(./scripts/fetch-signing-env.sh)"
#   ./build.sh --clean
#   ./scripts/fetch-signing-env.sh --clean      # wipe the credentials again
# They are EVTRACK_KEYSTORE_FILE / _PASSWORD and EVTRACK_KEY_ALIAS / _PASSWORD.
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Load local environment overrides (e.g. JAVA_HOME) if present
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -a
    # shellcheck disable=SC1091
    source "$SCRIPT_DIR/.env"
    set +a
fi

# The build requires a JDK with a compiler (JDK 17). The machine default java-21
# is JRE-only and Gradle fails with "does not provide the required capabilities:
# [JAVA_COMPILER]". Fall back to the standard JDK 17 path when available.
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi

# Force Gradle to use JAVA_HOME so it cannot auto-select a JRE-only toolchain.
GRADLE_JDK_ARG=""
if [ -n "${JAVA_HOME:-}" ]; then
    GRADLE_JDK_ARG="-Dorg.gradle.java.home=$JAVA_HOME"
fi

# Legacy path: the shared keys.sh file. Signing now comes from AWS Secrets
# Manager via scripts/fetch-signing-env.sh, but keep honouring keys.sh if a
# machine still has one.
KEYS_SH="${EVTRACK_KEYS_SH:-/home/janz/data/development/evtrack/release/keys.sh}"

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
    echo "         Fetch them first:  eval \"\$(./scripts/fetch-signing-env.sh)\""
    echo "         The release APK will be UNSIGNED - say no unless you know why."
    read -r -p "Continue without signing? [y/N] " response
    [[ "$response" =~ ^[Yy]$ ]] || exit 1
fi

./gradlew $GRADLE_JDK_ARG $CLEAN assembleRelease

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
