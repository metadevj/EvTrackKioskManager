#!/bin/bash
# =============================================================================
# Fetch Android release signing material from AWS Secrets Manager
# =============================================================================
#
# The keystore and its credentials live in one secret (shared with FrontDesk - the
# Kiosk Manager is signed with the same evtrack-release key):
#   evtrack/frontdesk/android-signing   (region eu-central-1)
# JSON fields: keystore_b64, keystore_password, key_alias, key_password
#
# Usage (the export lines go to stdout, everything else to stderr):
#   eval "$(./scripts/fetch-signing-env.sh)"    # then run ./build.sh --clean
#   ./scripts/fetch-signing-env.sh --clean      # remove the fetched keystore
#
# The keystore is written to a private runtime file and EVTRACK_* variables
# point at it. Run --clean after building; the file also disappears on reboot.
# Requires: aws CLI with access to the secret, python3.
#
# =============================================================================
set -euo pipefail

SECRET_ID="evtrack/frontdesk/android-signing"
REGION="eu-central-1"
RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}/evtrack-signing"
KEYSTORE_PATH="$RUNTIME_DIR/evtrack-frontdesk.keystore"

if [ "${1:-}" == "--clean" ]; then
    rm -rf "$RUNTIME_DIR"
    echo "Removed $RUNTIME_DIR" >&2
    exit 0
fi

umask 077
mkdir -p "$RUNTIME_DIR"

aws secretsmanager get-secret-value \
    --region "$REGION" --secret-id "$SECRET_ID" \
    --query SecretString --output text |
python3 -c '
import base64, json, shlex, sys
d = json.load(sys.stdin)
path = sys.argv[1]
with open(path, "wb") as f:
    f.write(base64.b64decode(d["keystore_b64"]))
print("export EVTRACK_KEYSTORE_FILE=" + shlex.quote(path))
print("export EVTRACK_KEYSTORE_PASSWORD=" + shlex.quote(d["keystore_password"]))
print("export EVTRACK_KEY_ALIAS=" + shlex.quote(d["key_alias"]))
print("export EVTRACK_KEY_PASSWORD=" + shlex.quote(d["key_password"]))
' "$KEYSTORE_PATH"

echo "Keystore fetched to $KEYSTORE_PATH (eval the output, then build)" >&2
