#!/usr/bin/env bash
# Install an OpenTether APK using one explicitly selected ADB executable,
# server port, and device serial. The standard `adb install -r` path is always
# attempted first. If Android/MIUI rejects it with
# INSTALL_FAILED_USER_RESTRICTED, retry through a unique temporary APK using
# `adb push` + `pm install -r`.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: adb-install.sh --serial SERIAL --apk PATH [options]

Options:
  --adb-bin PATH    ADB executable to use (default: adb)
  --adb-port PORT   ADB server port (default: 0 = ADB client default)
  --serial SERIAL   Device serial selected by the caller
  --apk PATH        APK file to install
  -h, --help        Show this help
EOF
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

ADB_BIN="adb"
ADB_PORT="0"
SERIAL=""
APK=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --adb-bin)
      [[ $# -ge 2 ]] || die "--adb-bin requires a path"
      ADB_BIN="$2"
      shift 2
      ;;
    --adb-port)
      [[ $# -ge 2 ]] || die "--adb-port requires a port"
      ADB_PORT="$2"
      shift 2
      ;;
    --serial)
      [[ $# -ge 2 ]] || die "--serial requires a device serial"
      SERIAL="$2"
      shift 2
      ;;
    --apk)
      [[ $# -ge 2 ]] || die "--apk requires a path"
      APK="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$SERIAL" ]] || die "--serial is required"
[[ -n "$APK" ]] || die "--apk is required"
[[ -f "$APK" ]] || die "APK not found: $APK"
[[ "$ADB_PORT" =~ ^[0-9]+$ ]] || die "--adb-port must be a number"
(( ADB_PORT >= 0 && ADB_PORT <= 65535 )) || die "--adb-port must be between 0 and 65535"

if [[ "$ADB_BIN" == */* ]]; then
  [[ -x "$ADB_BIN" ]] || die "ADB executable not found or not executable: $ADB_BIN"
else
  command -v "$ADB_BIN" >/dev/null 2>&1 \
    || die "ADB executable not found: $ADB_BIN"
fi

ADB_CMD=("$ADB_BIN")
if [[ "$ADB_PORT" != "0" ]]; then
  ADB_CMD+=("-P" "$ADB_PORT")
fi

run_adb() {
  "${ADB_CMD[@]}" "$@"
}

# Keep the standard path first. Only the specific OEM user-restriction failure
# is allowed to trigger the fallback.
if install_output="$(run_adb -s "$SERIAL" install -r "$APK" 2>&1)"; then
  printf '%s\n' "$install_output"
  exit 0
fi

printf '%s\n' "$install_output" >&2

if [[ "$install_output" != *"INSTALL_FAILED_USER_RESTRICTED"* ]]; then
  cat >&2 <<'EOF'
error: APK installation failed for a reason other than the OEM user
restriction. The fallback was not attempted. Review the ADB output above and
resolve the reported installation error before retrying.
EOF
  exit 1
fi

cat >&2 <<'EOF'
warning: Android/MIUI restricted the standard `adb install -r` path with
INSTALL_FAILED_USER_RESTRICTED. Trying a controlled fallback: copy the APK to a
unique temporary path on the device, run `pm install -r` there, then remove the
temporary file. This does not bypass Android security or approve any prompt.
EOF

TMP_APK="/data/local/tmp/opentether-${ADB_PORT}-$$-${RANDOM}.apk"
TMP_APK_CREATED=false
cleanup_tmp_apk() {
  if [[ "$TMP_APK_CREATED" == true ]]; then
    if ! run_adb -s "$SERIAL" shell rm -f "$TMP_APK" >/dev/null 2>&1; then
      printf 'warning: could not remove temporary APK %s from %s\n' \
        "$TMP_APK" "$SERIAL" >&2
    fi
    TMP_APK_CREATED=false
  fi
}
trap cleanup_tmp_apk EXIT

TMP_APK_CREATED=true
if ! push_output="$(run_adb -s "$SERIAL" push "$APK" "$TMP_APK" 2>&1)"; then
  printf '%s\n' "$push_output" >&2
  die "adb push failed; the fallback APK was not installed"
fi
printf '%s\n' "$push_output"

if ! pm_output="$(run_adb -s "$SERIAL" shell pm install -r "$TMP_APK" 2>&1)"; then
  printf '%s\n' "$pm_output" >&2
  cat >&2 <<'EOF'
error: the controlled `pm install` fallback failed. The device rejected the
installation even after the APK was copied locally. Install the APK manually on
the phone, or review the OEM's authorized developer-install setting. OpenTether
does not bypass Android security or approve prompts for you.
EOF
  exit 1
fi

printf '%s\n' "$pm_output"
if [[ "$pm_output" != *"Success"* ]]; then
  cat >&2 <<'EOF'
error: the controlled `pm install` fallback did not report Success. The device
rejected the installation. Install the APK manually on the phone, or review the
OEM's authorized developer-install setting. OpenTether does not bypass Android
security or approve prompts for you.
EOF
  exit 1
fi

printf 'info: APK installed through the MIUI-compatible fallback.\n'
