#!/bin/bash
# Stub-based tests for scripts/publish-release.sh: no AWS, no network. Each case runs the script
# from a throwaway git repo with stub aws/apksigner/curl on PATH.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT/scripts/publish-release.sh"
pass=0; fail=0

setup() {
  WORK="$(mktemp -d)"
  mkdir -p "$WORK/repo/scripts" "$WORK/bin" "$WORK/repo/dist"
  cp "$SCRIPT" "$WORK/repo/scripts/publish-release.sh"
  cd "$WORK/repo"
  git init -q && git config user.email t@t && git config user.name t
  echo "1.1.5" > VERSION
  printf 'fake-apk-bytes' > dist/evtrack-kiosk-manager-universal-release.apk
  git add -A && git commit -qm init && git tag v1.1.5
  cat > "$WORK/bin/apksigner" <<'EOF'
#!/bin/bash
exit "${STUB_APKSIGNER_RC:-0}"
EOF
  cat > "$WORK/bin/aws" <<'EOF'
#!/bin/bash
echo "aws $*" >> "$STUB_LOG"
if [ "$1 $2" = "s3api get-object" ]; then
  if [ -n "${STUB_REMOTE_MANIFEST:-}" ]; then cp "$STUB_REMOTE_MANIFEST" "${@: -1}"; exit 0; fi
  exit 254
fi
exit 0
EOF
  cat > "$WORK/bin/curl" <<'EOF'
#!/bin/bash
echo "curl $*" >> "$STUB_LOG"; echo 000; exit 0
EOF
  chmod +x "$WORK/bin/"*
  export PATH="$WORK/bin:$PATH" STUB_LOG="$WORK/stub.log"
  : > "$STUB_LOG"
  unset STUB_APKSIGNER_RC STUB_REMOTE_MANIFEST EVTRACK_RELEASES_VERSION
}

check() { # check <name> <condition-exit-code>
  if [ "$2" -eq 0 ]; then echo "ok   $1"; pass=$((pass+1)); else echo "FAIL $1"; fail=$((fail+1)); fi
}

# 1. dry-run manifest: product, version, versioned filename, role, sha, content type
setup
out=$(scripts/publish-release.sh --dry-run 2>&1); rc=$?
m=$(grep '^{' <<<"$out")
sha=$(sha256sum dist/evtrack-kiosk-manager-universal-release.apk | cut -d' ' -f1)
[ $rc -eq 0 ] \
  && [ "$(jq -r .product <<<"$m")" = "evtrack-kiosk-manager" ] \
  && [ "$(jq -r .version <<<"$m")" = "1.1.5" ] \
  && [ "$(jq -r '.files[0].name' <<<"$m")" = "evtrack-kiosk-manager-1.1.5-release.apk" ] \
  && [ "$(jq -r '.files[0].role' <<<"$m")" = "distribution" ] \
  && [ "$(jq -r '.files[0].sha256' <<<"$m")" = "$sha" ] \
  && [ "$(jq -r '.files[0].contentType' <<<"$m")" = "application/vnd.android.package-archive" ] \
  && [ "$(jq -r '.files | length' <<<"$m")" = "1" ] \
  && [ "$(jq -r .source.ref <<<"$m")" = "v1.1.5" ] \
  && grep -q "would upload: s3://evtrack-releases/releases/evtrack-kiosk-manager/1.1.5/evtrack-kiosk-manager-1.1.5-release.apk" <<<"$out" \
  && [ ! -s "$STUB_LOG" ]
check "dry-run manifest is correct and touches no AWS" $?

# 2. apksigner failure aborts
setup
out=$(STUB_APKSIGNER_RC=1 scripts/publish-release.sh --dry-run 2>&1); rc=$?
[ $rc -ne 0 ] && grep -q "failed 'apksigner verify'" <<<"$out" && [ ! -d dist/arcs ]
check "apksigner failure aborts before staging" $?

# 3. malformed VERSION aborts
setup
echo "1.1.5-rc1" > VERSION
out=$(scripts/publish-release.sh --dry-run 2>&1); rc=$?
[ $rc -ne 0 ] && grep -q "not a dotted numeric version" <<<"$out"
check "malformed VERSION aborts" $?

# 4. differing remote manifest aborts before any upload
setup
echo '{"files":[{"name":"evtrack-kiosk-manager-1.1.5-release.apk","sha256":"0000"}]}' > "$WORK/remote.json"
out=$(STUB_REMOTE_MANIFEST="$WORK/remote.json" ARCS_ADMIN_API_TOKEN=x scripts/publish-release.sh 2>&1); rc=$?
[ $rc -ne 0 ] && grep -q "DIFFERENT content" <<<"$out" && ! grep -q "aws s3 cp" "$STUB_LOG"
check "differing remote manifest aborts before any upload" $?

# 5. missing APK gives a clear error
setup
rm dist/evtrack-kiosk-manager-universal-release.apk
out=$(scripts/publish-release.sh --dry-run 2>&1); rc=$?
[ $rc -ne 0 ] && grep -q "APK not found" <<<"$out"
check "missing APK aborts with a clear message" $?

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
