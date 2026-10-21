#!/usr/bin/env bash
# Focused, device-free tests for scripts/adb-install.sh.
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="$ROOT/scripts/adb-install.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

MOCK="$TMP/adb"
APK="$TMP/fake.apk"
: > "$APK"

cat > "$MOCK" <<'MOCK_EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_LOG"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -P|-s) shift 2 ;;
    *) break ;;
  esac
done
op="${1:-}"
shift || true
case "$op" in
  install)
    case "${MOCK_MODE:-}" in
      standard_ok)
        echo "Success"
        exit 0
        ;;
      fallback_ok|fallback_fail|push_fail)
        echo "Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]" >&2
        exit 1
        ;;
      other)
        echo "Failure [INSTALL_FAILED_INVALID_APK: invalid]" >&2
        exit 1
        ;;
      *)
        echo "unexpected mock mode" >&2
        exit 99
        ;;
    esac
    ;;
  push)
    if [[ "${MOCK_MODE:-}" == "push_fail" ]]; then
      echo "adb: error: failed to copy APK" >&2
      exit 1
    fi
    exit 0
    ;;
  shell)
    sub="${1:-}"
    shift || true
    case "$sub" in
      pm)
        if [[ "${MOCK_MODE:-}" == "fallback_fail" ]]; then
          echo "Failure [INSTALL_FAILED_VERIFICATION_FAILURE]"
        else
          echo "Success"
        fi
        exit 0
        ;;
      rm)
        exit 0
        ;;
    esac
    ;;
esac
exit 99
MOCK_EOF
chmod +x "$MOCK"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

contains() {
  local file=$1 text=$2
  grep -Fq -- "$text" "$file" || fail "expected '$text' in $file"
}

not_contains() {
  local file=$1 text=$2
  if grep -Fq -- "$text" "$file"; then
    fail "did not expect '$text' in $file"
  fi
}

run_helper() {
  local mode=$1 log=$2 output=$3
  shift 3
  MOCK_MODE="$mode" MOCK_LOG="$log" \
    bash "$HELPER" --adb-bin "$MOCK" --serial TEST-SERIAL --apk "$APK" "$@" \
    > "$output" 2>&1
}

# 1. Standard install succeeds; no fallback is attempted.
LOG="$TMP/standard.log"; OUT="$TMP/standard.out"; : > "$LOG"
run_helper standard_ok "$LOG" "$OUT" --adb-port 5038
contains "$LOG" "-P 5038 -s TEST-SERIAL install -r"
not_contains "$LOG" " push "
not_contains "$LOG" " shell pm install"
echo "PASS: standard adb install path"

# 2. OEM restriction triggers push + pm install + temporary-file cleanup.
LOG="$TMP/fallback.log"; OUT="$TMP/fallback.out"; : > "$LOG"
run_helper fallback_ok "$LOG" "$OUT" --adb-port 5038
contains "$LOG" "-P 5038 -s TEST-SERIAL install -r"
contains "$LOG" "-P 5038 -s TEST-SERIAL push"
contains "$LOG" "-P 5038 -s TEST-SERIAL shell pm install -r /data/local/tmp/opentether-5038-"
contains "$LOG" "-P 5038 -s TEST-SERIAL shell rm -f /data/local/tmp/opentether-5038-"
contains "$OUT" "MIUI-compatible fallback"
echo "PASS: OEM-restricted fallback and cleanup"

# 3. Unrelated install failures must not trigger the fallback.
LOG="$TMP/other.log"; OUT="$TMP/other.out"; : > "$LOG"
if run_helper other "$LOG" "$OUT" --adb-port 5038; then
  fail "unrelated install failure unexpectedly succeeded"
fi
contains "$OUT" "fallback was not attempted"
not_contains "$LOG" " push "
not_contains "$LOG" " shell pm install"
echo "PASS: unrelated install failure does not trigger fallback"

# 4. A rejected fallback reports failure and still cleans up the temporary APK.
LOG="$TMP/rejected.log"; OUT="$TMP/rejected.out"; : > "$LOG"
if run_helper fallback_fail "$LOG" "$OUT" --adb-port 5038; then
  fail "rejected fallback unexpectedly succeeded"
fi
contains "$LOG" " push "
contains "$LOG" " shell pm install -r "
contains "$LOG" " shell rm -f /data/local/tmp/opentether-5038-"
contains "$OUT" "rejected the installation"
echo "PASS: rejected fallback fails safely and cleans up"

# 5. Port 0 preserves the ADB client default (no -P flag).
LOG="$TMP/default-port.log"; OUT="$TMP/default-port.out"; : > "$LOG"
run_helper standard_ok "$LOG" "$OUT"
contains "$LOG" "-s TEST-SERIAL install -r"
not_contains "$LOG" "-P "
echo "PASS: default ADB server port omits -P"

# 6. A failed push aborts before pm install and still attempts temp cleanup.
LOG="$TMP/push-fail.log"; OUT="$TMP/push-fail.out"; : > "$LOG"
if run_helper push_fail "$LOG" "$OUT" --adb-port 5038; then
  fail "failed push unexpectedly succeeded"
fi
contains "$LOG" " push "
not_contains "$LOG" " shell pm install"
contains "$LOG" " shell rm -f /data/local/tmp/opentether-5038-"
contains "$OUT" "adb push failed"
echo "PASS: failed push aborts and cleans up"

printf 'All adb-install tests passed.\n'
