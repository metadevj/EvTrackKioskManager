#!/bin/bash
# =============================================================================
# Hand a tablet back: release Device Owner and remove the Kiosk Manager
# =============================================================================
#
#   ./scripts/release-device-owner.sh            # the only attached device
#   ./scripts/release-device-owner.sh <serial>   # a specific one
#
# Android allows exactly two ways out of Device Owner: the owning app gives it
# up, or the device is factory reset. `adb shell dpm remove-active-admin` is NOT
# one of them - it refuses anything that is not a test-only build with
#
#     SecurityException: Attempt to remove non-test admin ComponentInfo{...}
#
# So this drives the Manager's own "Release Device Owner" button. Everything
# around that one tap is automated: the state is checked first, the app is
# brought up, the release is verified, and the Manager is then uninstalled.
#
# The tap is deliberately left to a person. Anything that could clear Device
# Owner without one would be a way for any app on the kiosk to unlock it.
# =============================================================================
set -euo pipefail

PKG="com.evtrack.kioskmanager"
ADMIN="$PKG/.AdminReceiver"

die() { echo "ERROR: $*" >&2; exit 1; }

DEVICE="${1:-}"
if [ -z "$DEVICE" ]; then
    mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    [ "${#devices[@]}" -eq 0 ] && die "no device attached"
    [ "${#devices[@]}" -gt 1 ] && die "more than one device attached; pass a serial: ${devices[*]}"
    DEVICE="${devices[0]}"
fi
ADB=(adb -s "$DEVICE")

echo "Device: $DEVICE ($("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'))"

"${ADB[@]}" shell "pm list packages $PKG" 2>/dev/null | grep -q "$PKG" \
    || die "$PKG is not installed on this device"

owner=$("${ADB[@]}" shell "dumpsys device_policy | grep -c '$PKG'" | tr -d '\r' || true)
if [ "${owner:-0}" -eq 0 ]; then
    echo "Not Device Owner. Uninstalling the Manager directly."
    "${ADB[@]}" uninstall "$PKG"
    exit 0
fi

echo
echo "This device is managed by the Kiosk Manager."
echo "Releasing means lockdown is lifted and it stops being managed. Making it"
echo "managed again needs the provisioning steps repeated."
read -r -p "Continue? [y/N] " reply
[[ "$reply" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 1; }

echo
echo "Opening the Kiosk Manager. On the tablet:"
echo "    tap 'Release Device Owner', then 'Release' in the dialog."
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

echo -n "Waiting for it to be released"
for _ in $(seq 1 60); do
    sleep 2
    echo -n "."
    left=$("${ADB[@]}" shell "dumpsys device_policy | grep -c '$PKG'" | tr -d '\r' || true)
    if [ "${left:-1}" -eq 0 ]; then
        echo
        echo "Released. Uninstalling the Manager."
        "${ADB[@]}" uninstall "$PKG"
        echo "Done. $DEVICE is no longer managed."
        exit 0
    fi
done

echo
die "still Device Owner after two minutes. Was the button tapped? Nothing has been uninstalled."
