#!/bin/bash
# =============================================================================
# Publish the signed Kiosk Manager APK to the EvTrack APK CDN
# (downloads.evtrack.com/public/apk) via S3/rclone (Cloudflare R2).
# =============================================================================
#
# Follows the live CDN convention — version sorted by FOLDER, constant filename:
#
#   evtrack-kiosk-manager/<version>.<build>/evtrack-kiosk-manager-universal-release.apk
#   evtrack-kiosk-manager/<variant>/evtrack-kiosk-manager-universal-release.json
#        { "apps": [ { name, version, build, sha256 } ] }
#
# version/build come from the VERSION file (MAJOR.MINOR.BUILD): version = the
# MAJOR.MINOR part, build = the BUILD part (1.0.2 -> version "1.0", build "2").
#
# Usage:
#   ./publish-cdn.sh              # publish dist/<apk> to the CDN
#   ./publish-cdn.sh --dry-run    # stage + show the rclone plan, upload nothing
#   ./publish-cdn.sh <apk>        # publish a specific APK file
#
# Uploads with `rclone copy` (NO deletes — old versions are preserved).
# Requires S3/rclone.conf with an [evtrack-downloads] remote
# (see S3/rclone.conf.example). Nothing secret is committed.
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ── App-specific CDN identity ───────────────────────────────────────────────
FOLDER="evtrack-kiosk-manager"
NAME="EvTrackKioskManager"
VARIANT="latest"
APK_NAME="${FOLDER}-universal-release.apk"
JSON_NAME="${FOLDER}-universal-release.json"

# ── Config ──────────────────────────────────────────────────────────────────
DOWNLOADS_REMOTE="${EVTRACK_DOWNLOADS_REMOTE:-evtrack-downloads:evtrack-downloads}"
KEY_PREFIX="public/apk"                 # object-key prefix under the bucket root
CONF="${EVTRACK_RCLONE_CONF:-$SCRIPT_DIR/S3/rclone.conf}"
STAGE_ROOT="$SCRIPT_DIR/cdn"            # local staging (gitignored)

# ── Args ────────────────────────────────────────────────────────────────────
DRY_RUN=""
APK="dist/$APK_NAME"
for a in "$@"; do
    case "$a" in
        --dry-run) DRY_RUN="--dry-run" ;;
        *) APK="$a" ;;
    esac
done

if [ ! -f "$APK" ]; then
    echo "Error: APK not found: $APK"
    echo "  Build it first:  ./build.sh --clean"
    exit 1
fi

# ── Version / build from VERSION ────────────────────────────────────────────
VER=$(cat VERSION | tr -d '[:space:]')      # e.g. 1.0.2
VERSION="${VER%.*}"                          # 1.0
BUILD="${VER##*.}"                           # 2
if [ -z "$VERSION" ] || [ -z "$BUILD" ] || [ "$VERSION" = "$BUILD" ]; then
    echo "Error: could not parse VERSION='$VER' (expected MAJOR.MINOR.BUILD)"; exit 1
fi

# ── Stage into the CDN structure ────────────────────────────────────────────
VER_DIR="$STAGE_ROOT/$FOLDER/${VERSION}.${BUILD}"
PTR_DIR="$STAGE_ROOT/$FOLDER/$VARIANT"
mkdir -p "$VER_DIR" "$PTR_DIR"
cp -f "$APK" "$VER_DIR/$APK_NAME"

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
cat > "$PTR_DIR/$JSON_NAME" <<EOF
{
  "apps": [
    {
      "name": "$NAME",
      "version": "$VERSION",
      "build": "$BUILD",
      "sha256": "$SHA"
    }
  ]
}
EOF

echo "Staged (version sorted by folder, constant filename):"
echo "  APK      $FOLDER/${VERSION}.${BUILD}/$APK_NAME"
echo "  pointer  $FOLDER/$VARIANT/$JSON_NAME   -> v$VERSION build $BUILD"
echo "  sha256   $SHA"
echo ""

# ── Upload to R2 (copy = no deletes; old versions preserved) ────────────────
if [ ! -f "$CONF" ]; then
    echo "rclone config not found at $CONF."
    echo "  Copy S3/rclone.conf.example -> S3/rclone.conf and fill in the"
    echo "  [evtrack-downloads] R2 credentials, then re-run."
    echo "  (The staged files above are ready under cdn/.)"
    exit 1
fi
if ! command -v rclone &>/dev/null; then
    echo "rclone not installed. Install it, then re-run to upload."
    exit 1
fi

DEST="$DOWNLOADS_REMOTE/$KEY_PREFIX/$FOLDER"
echo "Uploading $FOLDER/ -> $DEST ${DRY_RUN:+(dry-run)}"
rclone copy "$STAGE_ROOT/$FOLDER" "$DEST" --config "$CONF" --progress $DRY_RUN

echo ""
if [ -n "$DRY_RUN" ]; then
    echo "Dry run only — nothing uploaded."
else
    echo "Done. Live at:"
    echo "  https://downloads.evtrack.com/$KEY_PREFIX/$FOLDER/$VARIANT/$JSON_NAME"
    echo "  https://downloads.evtrack.com/$KEY_PREFIX/$FOLDER/${VERSION}.${BUILD}/$APK_NAME"
fi
