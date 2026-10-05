#!/bin/bash
# =============================================================================
# Install the Kiosk Manager onto one device
# =============================================================================
#
#   ./scripts/install.sh <serial>            # install dist/ APK on that device
#   ./scripts/install.sh <serial> <apk>      # install a specific APK
#   ./scripts/install.sh                     # lists attached devices and stops
#
# The serial is REQUIRED. There is usually more than one thing attached - a
# tablet, a kiosk over wifi, a stale emulator - and installing a Device Owner
# app onto the wrong one is not something adb will ask you about.
#
# Installs only. It deliberately does NOT set Device Owner: that is close to
# one-way (see README, "Know what this commits you to") and belongs in a
# separate, deliberate step.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
PKG="com.evtrack.kioskmanager"
DEFAULT_APK="$ROOT_DIR/dist/evtrack-kiosk-manager-universal-release.apk"

die() { echo "ERROR: $*" >&2; exit 1; }

list_devices() {
    echo "Attached devices:"
    adb devices -l | awk 'NR>1 && NF {
        serial = $1; state = $2; model = "";
        for (i = 3; i <= NF; i++) if ($i ~ /^model:/) { model = substr($i, 7); gsub(/_/, "-", model) }
        printf "  %-26s %-12s %s\n", serial, state, model
    }'
}

DEVICE="${1:-}"
if [ -z "$DEVICE" ]; then
    echo "Usage: $0 <serial> [apk]"
    echo
    list_devices
    exit 1
fi

APK="${2:-$DEFAULT_APK}"
[ -f "$APK" ] || die "APK not found: $APK
  Build one first:  ./build.sh        (signed release, also staged into dist/)"

adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$DEVICE" \
    || { echo "ERROR: '$DEVICE' is not attached and ready." >&2; echo >&2; list_devices >&2; exit 1; }

ADB=(adb -s "$DEVICE")
MODEL=$("${ADB[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r')

# What is going on, before anything changes.
AAPT2=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ -n "$AAPT2" ]; then
    NEW_VERSION=$("$AAPT2" dump badging "$APK" 2>/dev/null \
        | grep -oE "versionName='[^']*'" | head -1 | cut -d"'" -f2)
else
    NEW_VERSION="unknown (aapt2 not found)"
fi
INSTALLED=$("${ADB[@]}" shell "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null \
    | tr -d '\r' | cut -d= -f2 || true)

echo "Device:    $DEVICE ($MODEL)"
echo "APK:       $(basename "$APK")  ->  $NEW_VERSION"
echo "Installed: ${INSTALLED:-not installed}"

# Device Owner cannot be uninstalled, so a signature clash there is a dead end
# worth naming now rather than after a confusing failure.
OWNER=$("${ADB[@]}" shell "dumpsys device_policy | grep -c '$PKG'" 2>/dev/null | tr -d '\r' || true)
[ "${OWNER:-0}" -gt 0 ] && echo "Note:      this device is Device Owner-managed by the Manager"

echo
echo "Installing..."
if "${ADB[@]}" install -r "$APK"; then
    echo
    echo "Installed: $("${ADB[@]}" shell "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null | tr -d '\r' | cut -d= -f2 || true)"
    exit 0
fi

# The common failure, with the reason rather than adb's wording.
echo >&2
echo "Install failed. The usual cause is a signing mismatch: a debug build cannot" >&2
echo "replace a release-signed one, or the other way round." >&2
if [ "${OWNER:-0}" -gt 0 ]; then
    echo >&2
    echo "This device is Device Owner-managed, so it cannot simply be uninstalled first." >&2
    echo "Release it, then install:  ./scripts/release-device-owner.sh $DEVICE" >&2
else
    echo >&2
    echo "Uninstall first, then run this again (app data on the device is lost):" >&2
    echo "  adb -s $DEVICE uninstall $PKG" >&2
fi
exit 1
