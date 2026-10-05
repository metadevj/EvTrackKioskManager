#!/bin/bash
# Publish a signed Kiosk Manager APK to the evtrack-releases bucket and register it with ARCS.
# Usage: scripts/publish-release.sh [<apk>] [--dry-run] [--skip-register]
#
# Default APK: dist/evtrack-kiosk-manager-universal-release.apk (staged by push-release.sh), else
# app/build/outputs/apk/release/app-release.apk. Version comes from VERSION.
#
# Adapted from EvTrackFrontDesk scripts/publish-release.sh. Spec: EvTrackARCS
# docs/superpowers/specs/2026-10-05-kiosk-manager-os-releases-design.md. Every registration lands
# on the BETA channel; an ARCS admin promotes it to STABLE, which is what kiosks self-update from.
# This does NOT replace publish-cdn.sh: QR provisioning still downloads the manager from the CDN.
#
# Env: EVTRACK_RELEASES_AWS_PROFILE (default "default"), EVTRACK_RELEASES_VERSION,
#      EVTRACK_RELEASES_BUILDTIME, ARCS_BASE_URL, ARCS_ADMIN_API_TOKEN.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

PRODUCT="evtrack-kiosk-manager"
BUCKET="${EVTRACK_RELEASES_BUCKET:-evtrack-releases}"
PREFIX="${EVTRACK_RELEASES_PREFIX:-releases}"
PROFILE="${EVTRACK_RELEASES_AWS_PROFILE:-default}"
ARCS_BASE_URL="${ARCS_BASE_URL:-https://arcs.evtrack.com}"

DRY_RUN=false; SKIP_REGISTER=false; APK=""
for a in "$@"; do case "$a" in
  --dry-run) DRY_RUN=true ;;
  --skip-register) SKIP_REGISTER=true ;;
  -*) echo "ERROR: unknown option $a" >&2; exit 1 ;;
  *) APK="$a" ;;
esac; done
if [ -z "$APK" ]; then
  for candidate in dist/evtrack-kiosk-manager-universal-release.apk \
                   app/build/outputs/apk/release/app-release.apk; do
    if [ -f "$candidate" ]; then APK="$candidate"; break; fi
  done
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "ERROR: APK not found${APK:+: $APK} - build it first (./build.sh --clean)" >&2; exit 1
fi

VERSION="${EVTRACK_RELEASES_VERSION:-$(tr -d '[:space:]' < VERSION)}"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] \
  || { echo "ERROR: '$VERSION' is not a dotted numeric version (e.g. 1.1.5)" >&2; exit 1; }

# Kiosks refuse a self-update whose signing certificate differs from the installed manager's,
# so an unsigned or debug-signed build promoted to STABLE would strand the fleet. Check here.
command -v apksigner >/dev/null \
  || { echo "ERROR: apksigner not on PATH (Android build-tools) - needed to verify the signature" >&2; exit 1; }
apksigner verify "$APK" >/dev/null 2>&1 \
  || { echo "ERROR: $APK failed 'apksigner verify' - is it a signed release build?" >&2; exit 1; }

STAGE_DIR="dist/arcs/$VERSION"
rm -rf "$STAGE_DIR"; mkdir -p "$STAGE_DIR"
cp -f "$APK" "$STAGE_DIR/$PRODUCT-$VERSION-release.apk"
KEY_BASE="$PREFIX/$PRODUCT/$VERSION"

TAG="v$VERSION"
BUILD_TIME="${EVTRACK_RELEASES_BUILDTIME:-$(git log -1 --format=%cI "$TAG" 2>/dev/null || date -u -r "$APK" +%FT%TZ)}"
SRC_REPO="EvTrackKioskManager"
SRC_COMMIT="$(git rev-parse "$TAG^{commit}" 2>/dev/null || git rev-parse HEAD 2>/dev/null || echo unknown)"
SRC_REF="$TAG"
NOTES=""

content_type() { case "$1" in
  *.apk) echo application/vnd.android.package-archive ;;
  *.json) echo application/json ;;
  *) echo application/octet-stream ;;
esac; }

file_role() { case "$1" in
  *.apk) echo distribution ;;
  *) echo file ;;
esac; }

# ---- Common publish core (keep in sync with EvTrackOS-RPi5 scripts/publish-release.sh) ----

build_manifest() {
  local files_json="[]" f name size sha
  while IFS= read -r f; do
    name="$(basename "$f")"
    size=$(stat -c %s "$f")
    sha=$(sha256sum "$f" | awk '{print $1}')
    files_json=$(jq -c --arg n "$name" --arg r "$(file_role "$name")" \
      --argjson s "$size" --arg h "$sha" --arg ct "$(content_type "$name")" \
      '. += [{name:$n, role:$r, size:$s, sha256:$h, contentType:$ct}]' <<<"$files_json")
  done < <(find "$STAGE_DIR" -maxdepth 1 -type f | sort)
  [ "$(jq length <<<"$files_json")" -gt 0 ] || { echo "ERROR: nothing staged in $STAGE_DIR" >&2; exit 1; }
  jq -c -n --arg p "$PRODUCT" --arg v "$VERSION" --arg bt "$BUILD_TIME" --arg bu "$(whoami)" \
    --arg repo "$SRC_REPO" --arg commit "$SRC_COMMIT" --arg ref "$SRC_REF" \
    --arg notes "$NOTES" --argjson files "$files_json" \
    '{schemaVersion: 1, product: $p, version: $v, buildTime: $bt, buildUser: $bu,
      source: {repo: $repo, commit: $commit, ref: $ref},
      channelHint: "beta", files: $files, notes: $notes}'
}

precheck_and_upload() {
  local remote_file remote_manifest name ct sha manifest_file
  remote_file="$(mktemp)"
  if aws s3api get-object --bucket "$BUCKET" --key "$KEY_BASE/manifest.json" \
      --profile "$PROFILE" "$remote_file" >/dev/null 2>&1; then
    remote_manifest=$(cat "$remote_file"); rm -f "$remote_file"
    if [ "$(jq -S .files <<<"$remote_manifest")" = "$(jq -S .files <<<"$MANIFEST")" ]; then
      echo "Version $PRODUCT/$VERSION already published with identical content - resuming."
      return 0
    fi
    echo "ERROR: $PRODUCT/$VERSION is already published with DIFFERENT content." >&2
    echo "Versions are immutable - bump the version and publish again." >&2
    exit 1
  fi
  rm -f "$remote_file"
  # Files first, manifest LAST: a manifest-less prefix is an ignorable partial; re-runs resume it.
  while IFS= read -r name; do
    ct=$(jq -r --arg n "$name" '.files[] | select(.name==$n) | .contentType' <<<"$MANIFEST")
    sha=$(jq -r --arg n "$name" '.files[] | select(.name==$n) | .sha256' <<<"$MANIFEST")
    echo "Uploading $name..."
    aws s3 cp "$STAGE_DIR/$name" "s3://$BUCKET/$KEY_BASE/$name" \
      --profile "$PROFILE" --content-type "$ct" \
      --content-disposition "attachment; filename=\"$name\"" \
      --cache-control "public, max-age=31536000, immutable" \
      --metadata sha256="$sha" >/dev/null
  done < <(jq -r '.files[].name' <<<"$MANIFEST")
  manifest_file="$(mktemp)"
  echo "$MANIFEST" > "$manifest_file"
  aws s3 cp "$manifest_file" "s3://$BUCKET/$KEY_BASE/manifest.json" \
    --profile "$PROFILE" --content-type application/json >/dev/null
  rm -f "$manifest_file"
}

verify_remote() {
  local name expect_sha expect_size head
  while IFS= read -r name; do
    expect_sha=$(jq -r --arg n "$name" '.files[] | select(.name==$n) | .sha256' <<<"$MANIFEST")
    expect_size=$(jq -r --arg n "$name" '.files[] | select(.name==$n) | .size' <<<"$MANIFEST")
    head=$(aws s3api head-object --bucket "$BUCKET" --key "$KEY_BASE/$name" --profile "$PROFILE")
    [ "$(jq -r '.Metadata.sha256' <<<"$head")" = "$expect_sha" ] \
      || { echo "ERROR: sha256 mismatch on $name after upload" >&2; exit 1; }
    [ "$(jq -r '.ContentLength' <<<"$head")" = "$expect_size" ] \
      || { echo "ERROR: size mismatch on $name after upload" >&2; exit 1; }
  done < <(jq -r '.files[].name' <<<"$MANIFEST")
  echo "Verified $(jq '.files | length' <<<"$MANIFEST") file(s) at s3://$BUCKET/$KEY_BASE/"
}

register_with_arcs() {
  if [ -z "${ARCS_ADMIN_API_TOKEN:-}" ]; then
    ARCS_ADMIN_API_TOKEN=$(aws secretsmanager get-secret-value \
      --secret-id "${ARCS_TOKEN_SECRET_ID:-evtrack/arcs/releases-api-token}" \
      --region "${ARCS_TOKEN_SECRET_REGION:-eu-central-1}" --profile "$PROFILE" \
      --query SecretString --output text 2>/dev/null) || true
  fi
  : "${ARCS_ADMIN_API_TOKEN:?ARCS_ADMIN_API_TOKEN is required - set it, or make sure profile $PROFILE can read the Secrets Manager token (or pass --skip-register)}"
  local http_code body_file
  body_file="$(mktemp)"
  http_code=$(curl -sS -o "$body_file" -w '%{http_code}' \
    -X POST "$ARCS_BASE_URL/api/v1/admin/releases" \
    -H "Authorization: Bearer $ARCS_ADMIN_API_TOKEN" \
    -H "Content-Type: application/json" \
    --data-binary "$MANIFEST") \
    || { echo "ERROR: ARCS unreachable at $ARCS_BASE_URL - uploaded but UNREGISTERED. Re-run to retry." >&2; exit 1; }
  if [ "$http_code" = "201" ] || [ "$http_code" = "200" ]; then
    echo "Registered with ARCS: $(jq -c . "$body_file" 2>/dev/null || cat "$body_file")"
    echo "It is on BETA. Promote it to STABLE in ARCS (Admin > Releases) to roll it out."
    rm -f "$body_file"
  else
    echo "ERROR: ARCS registration failed (HTTP $http_code) - uploaded but UNREGISTERED." >&2
    cat "$body_file" >&2; rm -f "$body_file"; exit 1
  fi
}

MANIFEST="$(build_manifest)"

if $DRY_RUN; then
  echo "DRY RUN - nothing uploaded or registered."
  echo "$MANIFEST"
  jq -r --arg kb "$KEY_BASE" --arg b "$BUCKET" '.files[] | "would upload: s3://\($b)/\($kb)/\(.name)"' <<<"$MANIFEST"
  echo "would upload: s3://$BUCKET/$KEY_BASE/manifest.json (last)"
  $SKIP_REGISTER || echo "would register: POST $ARCS_BASE_URL/api/v1/admin/releases"
  exit 0
fi

precheck_and_upload
verify_remote
if $SKIP_REGISTER; then
  echo "Registered: SKIPPED (--skip-register). Uploaded but invisible in ARCS until registered."
  exit 0
fi
register_with_arcs
