#!/usr/bin/env bash
# shellcheck disable=SC2015
# SC2015 is intentionally used below for fail-fast command chains where the
# final branch calls error/warn after a failed command.
# ══════════════════════════════════════════════════════════════════════════════
#   OpenTether (NetcoN) Setup Script - v0.9.5
#  | Tested on Debian / Ubuntu (amd64 + arm64)
#
#  Usage:
#    sudo ./setup.sh              # ADB transport  (default, USB debugging required)
#    sudo ./setup.sh --aoa        # AOA transport  (experimental, not validated)
#    sudo ./setup.sh --build-only # build binaries only, don't start relay
#    sudo ./setup.sh -v           # verbose relay output
#    sudo ./setup.sh --adb-bin /path/to/adb --adb-port 5038
#                                 # select the ADB executable/server for WSL
#    sudo ./setup.sh --help       # show this help
# ══════════════════════════════════════════════════════════════════════════════
export PATH=$PATH:/usr/local/go/bin:/home/${SUDO_USER:-root}/go/bin
set -euo pipefail

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# Tracks whether this script created the ADB reverse mapping. Cleanup removes
# the mapping only when this remains true.
REVERSE_OWNED=false
DEVICE=""
ADB_BIN="adb"
ADB_PORT="0"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

info()    { echo -e "${GREEN}[✔]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[✘]${NC} $1"; exit 1; }
section() { echo -e "\n${CYAN}${BOLD}── $1 ──${NC}"; }

# Run every setup-time ADB command through the same executable and server port
# that will be passed to the relay watcher. This prevents a WSL setup from
# silently falling back to a different Linux ADB server.
adb_cmd() {
  if [[ "$ADB_PORT" != "0" ]]; then
    "$ADB_BIN" -P "$ADB_PORT" "$@"
  else
    "$ADB_BIN" "$@"
  fi
}

# ── Help ──────────────────────────────────────────────────────────────────────
usage() {
  echo -e "${BOLD}OpenTether setup v0.9.5${NC}"
  echo ""
  echo "  sudo ./setup.sh              ADB transport (USB debugging required)"
  echo "  sudo ./setup.sh --aoa        AOA transport (experimental, not validated)"
  echo "  sudo ./setup.sh --build-only Build relay + APK only, don't start"
  echo "  sudo ./setup.sh -v           Verbose relay logging"
  echo "  sudo ./setup.sh --adb-bin PATH --adb-port PORT"
  echo "                               Select the ADB executable/server (WSL/Windows)"
  echo "  sudo ./setup.sh --help       Show this help"
  echo ""
  echo "Flags can be combined:  sudo ./setup.sh --aoa -v"
  exit 0
}

# ── Parse flags ───────────────────────────────────────────────────────────────
USE_AOA=false
BUILD_ONLY=false
RELAY_EXTRA_FLAGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --aoa)
      USE_AOA=true
      RELAY_EXTRA_FLAGS+=(--aoa)
      shift
      ;;
    --build-only)
      BUILD_ONLY=true
      shift
      ;;
    --adb-bin)
      [[ $# -ge 2 ]] || error "--adb-bin requires a path"
      ADB_BIN="$2"
      shift 2
      ;;
    --adb-port)
      [[ $# -ge 2 ]] || error "--adb-port requires a port"
      ADB_PORT="$2"
      shift 2
      ;;
    --help|-h)
      usage
      ;;
    *)
      RELAY_EXTRA_FLAGS+=("$1")
      shift
      ;;
  esac
done

[[ "$ADB_PORT" =~ ^[0-9]+$ ]] \
  || error "--adb-port must be a number between 0 and 65535"
(( 10#$ADB_PORT >= 0 && 10#$ADB_PORT <= 65535 )) \
  || error "--adb-port must be between 0 and 65535"

if [[ "$ADB_BIN" != "adb" ]]; then
  [[ -x "$ADB_BIN" ]] \
    || error "ADB executable not found or not executable: $ADB_BIN"
fi

ADB_DISPLAY="$ADB_BIN"
if [[ "$ADB_PORT" != "0" ]]; then
  ADB_DISPLAY="$ADB_BIN -P $ADB_PORT"
fi

# ── Banner ────────────────────────────────────────────────────────────────────
echo -e "${BOLD}"
echo "  ╔═══════════════════════════════╗"
echo "  ║   OpenTether  v0.9.5  setup   ║"
echo "  ╚═══════════════════════════════╝"
echo -e "${NC}"
echo "  Transport : $(if $USE_AOA; then echo 'AOA (experimental, not validated)'; else echo 'ADB (TCP over USB, USB debugging required)'; fi)"
echo "  Mode      : $(if $BUILD_ONLY; then echo 'build only'; else echo 'build + run'; fi)"
if ! $USE_AOA; then
  echo "  ADB       : $ADB_DISPLAY"
fi
echo ""

# ── 1. Root check ─────────────────────────────────────────────────────────────
section "Checking permissions"
[[ $EUID -eq 0 ]] || error "This script must be run with sudo:  sudo ./setup.sh"
info "Running as root"

# ── 2. Dependencies ───────────────────────────────────────────────────────────
section "Installing dependencies"

apt-get update -qq

install_if_missing() {
  local cmd=$1 pkg=${2:-$1}
  if ! command -v "$cmd" &>/dev/null; then
    warn "$cmd not found — installing $pkg..."
    apt-get install -y "$pkg" &>/dev/null \
      && info "$cmd installed" \
      || error "Failed to install $pkg. Run: apt-get install $pkg"
  else
    info "$cmd already present"
  fi
}

install_if_missing iptables  iptables
install_if_missing ip        iproute2
install_if_missing java      openjdk-17-jdk

if ! $USE_AOA; then
  if [[ "$ADB_BIN" == "adb" ]]; then
    install_if_missing adb adb
  else
    info "Using configured ADB executable: $ADB_BIN"
  fi
fi

if $USE_AOA; then
  if ! dpkg -s libusb-1.0-0-dev &>/dev/null 2>&1; then
    warn "libusb-1.0-0-dev not found — installing..."
    apt-get install -y libusb-1.0-0-dev &>/dev/null \
      && info "libusb installed" \
      || error "Failed to install libusb-1.0-0-dev. Run: apt-get install libusb-1.0-0-dev"
  else
    info "libusb-1.0-0-dev already present"
  fi
fi

# Go: install from upstream if missing or below minimum required version
GO_MIN="1.25.0"
need_go=false
if ! command -v go &>/dev/null; then
  need_go=true
  warn "Go not found"
else
  GO_CURRENT=$(go version | sed -E 's/.* go([0-9]+\.[0-9]+\.[0-9]+).*/\1/')
  if [[ "$(printf '%s\n' "$GO_MIN" "$GO_CURRENT" | sort -V | head -1)" != "$GO_MIN" ]]; then
    warn "Go $GO_CURRENT is older than required $GO_MIN — upgrading"
    need_go=true
  else
    info "Go $GO_CURRENT already present"
  fi
fi

if $need_go; then
  GO_VERSION=$(curl -fsSL "https://go.dev/VERSION?m=text" | head -1 | sed 's/go//')
  ARCH=$(dpkg --print-architecture)
  case $ARCH in
    amd64) GO_ARCH="amd64" ;;
    arm64) GO_ARCH="arm64" ;;
    *)     error "Unsupported architecture: $ARCH — install Go manually from https://go.dev/dl/" ;;
  esac
  info "Downloading Go ${GO_VERSION} (${GO_ARCH})..."
  # Download first so a transient network failure does not remove the
  # currently working Go installation.
  GO_TARBALL="$(mktemp)"
  curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" -o "$GO_TARBALL" \
    || { rm -f "$GO_TARBALL"; error "Failed to download Go. Check network access and retry."; }
  rm -rf /usr/local/go
  tar -C /usr/local -xzf "$GO_TARBALL"
  rm -f "$GO_TARBALL"
  ln -sf /usr/local/go/bin/go /usr/local/bin/go
  info "Go ${GO_VERSION} installed"
fi

# ── 3. Build the relay ────────────────────────────────────────────────────────
section "Building relay"

if $USE_AOA; then
  # AOA dependencies are already declared in go.mod. Do not run "go get"
  # during setup: it can mutate the checkout and make builds non-reproducible.
  go build -tags aoa -o relay-bin ./cmd/opentether \
    || error "AOA relay build failed — check that libusb-1.0-0-dev and the Go dependencies are available"
  info "relay (AOA) built → ./relay-bin"
else
  go build -o relay-bin ./cmd/opentether \
    || error "Relay build failed — run 'go build -o relay-bin ./cmd/opentether' to see the full error"
  info "relay built → ./relay-bin"
fi

# ── 4. Build the Android app ──────────────────────────────────────────────────
section "Building Android app"

cd android-client
chmod +x gradlew

GRADLE_USER="${SUDO_USER:-root}"
if [[ "$GRADLE_USER" != "root" ]]; then
  sudo -u "$GRADLE_USER" env PATH="$PATH" ./gradlew assembleDebug --quiet \
    || ./gradlew assembleDebug --quiet \
    || error "Android build failed — check JDK 17+ is installed: java -version"
else
  ./gradlew assembleDebug --quiet \
    || error "Android build failed — check JDK 17+ is installed: java -version"
fi

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
VERSION="$(git describe --tags --always 2>/dev/null || echo dev)"
APK_NAME="OpenTether-${VERSION}-android.apk"
cp "$APK_SRC" "../$APK_NAME"
info "APK built → ./$APK_NAME"
cd ..

# ── 5. Build-only exit ────────────────────────────────────────────────────────
if $BUILD_ONLY; then
  echo ""
  info "Build complete."
  echo ""
  echo "  Relay binary  →  ./relay-bin"
  echo "  Android APK   →  ./$APK_NAME"
  echo ""
  if ! $USE_AOA; then
    echo "  To run manually:"
    echo "    $ADB_DISPLAY install $APK_NAME"
    if [[ "$ADB_BIN" != "adb" || "$ADB_PORT" != "0" ]]; then
      echo "    sudo ./relay-bin --adb-bin \"$ADB_BIN\" --adb-port $ADB_PORT"
    else
      echo "    sudo ./relay-bin    # sets up adb reverse automatically"
    fi
  else
    echo "  To run manually:"
    echo "    $ADB_DISPLAY install $APK_NAME     # one-time, needs USB debugging just for install"
    echo "    sudo ./relay-bin --aoa    # connect cable — phone prompts to open app"
  fi
  echo ""
  exit 0
fi

# ── 6. Phone connection check ─────────────────────────────────────────────────
section "Checking phone connection"

if $USE_AOA; then
  if command -v lsusb &>/dev/null; then
    if lsusb | grep -qi "android\|google\|samsung\|xiaomi\|huawei\|motorola\|oneplus\|oppo"; then
      info "Android device detected on USB"
    else
      warn "No Android device detected — connect your phone over USB and continue"
      warn "USB debugging is NOT required for AOA mode"
    fi
  else
    warn "lsusb not available — install 'usbutils' to enable USB device detection"
  fi
else
  adb_cmd start-server &>/dev/null || true
  DEVICE=$(adb_cmd devices 2>/dev/null | awk 'NR>1 && /\tdevice$/ {print $1; exit}')
  if [[ -z "$DEVICE" ]]; then
    echo ""
    error "No authorised Android device found.

  Checklist:
    1.  Cable is plugged in
    2.  Settings → Developer Options → USB Debugging is ON
    3.  You tapped 'Allow' on the USB debugging prompt on the phone
    4.  Running '$ADB_DISPLAY devices' shows your device as 'device' (not 'unauthorized')

  Once fixed, re-run:  sudo ./setup.sh"
  fi
  info "Device found: $DEVICE"
fi

# ── 7. Install APK ────────────────────────────────────────────────────────────
section "Installing app on device"

if ! $USE_AOA; then
  if bash "$SCRIPT_DIR/scripts/adb-install.sh" \
      --adb-bin "$ADB_BIN" \
      --adb-port "$ADB_PORT" \
      --serial "$DEVICE" \
      --apk "$APK_NAME"; then
    info "App installed on $DEVICE"
  else
    error "APK installation failed — try manually:  $ADB_DISPLAY -s $DEVICE install -r $APK_NAME"
  fi
else
  warn "AOA mode — you need ADB once to install the APK (USB debugging required just for this):"
  warn "  $ADB_DISPLAY install -r $APK_NAME"
  warn "After installation, USB debugging can be disabled."
fi

# ── 8. ADB reverse tunnel (ADB transport only) ────────────────────────────────
if ! $USE_AOA; then
  section "Setting up ADB reverse tunnel"
  # The relay watches for devices and re-runs this on reconnect automatically,
  # but running it now means the very first connection works immediately.
  # A mapping that already exists is reused but never adopted, so this script
  # cannot remove a tunnel another process owns.
  if adb_cmd -s "$DEVICE" reverse --list 2>/dev/null | grep -Eq 'tcp:8765[[:space:]]+tcp:8765'; then
    info "ADB reverse tunnel already present for $DEVICE (leaving ownership alone)"
  elif adb_cmd -s "$DEVICE" reverse tcp:8765 tcp:8765; then
    REVERSE_OWNED=true
    info "ADB reverse tunnel active  (phone:8765 → PC:8765)"
  else
    warn "adb reverse failed — relay will retry automatically when phone reconnects"
  fi
fi

# ── 9. Launch app ─────────────────────────────────────────────────────────────
section "Launching app"

if ! $USE_AOA; then
  adb_cmd -s "$DEVICE" shell am start -n com.opentether/.MainActivity &>/dev/null \
    && info "OpenTether launched on device" \
    || warn "Auto-launch failed — open OpenTether manually on the phone"
  echo ""
  warn "Tap 'Connect' and accept the system VPN consent dialog on first use."
else
  echo ""
  warn "Connect your phone over USB."
  warn "Phone will show 'Open OpenTether for this accessory?' — tap OK once."
  warn "After that the app opens and connects automatically on every cable plug-in."
fi

# ── 10. Start relay ───────────────────────────────────────────────────────────
section "Starting relay"
echo ""

if $USE_AOA; then
  info "Waiting for Android device over USB (AOA mode)..."
else
  info "Relay running — watching for device connections, adb reverse is automatic"
fi

echo ""
echo "  Ctrl+C to stop"
echo ""

cleanup() {
  echo ""
  section "Shutting down"
  if ! $USE_AOA && [[ "$REVERSE_OWNED" == true && -n "$DEVICE" ]]; then
    if adb_cmd -s "$DEVICE" reverse --list 2>/dev/null | grep -Eq 'tcp:8765[[:space:]]+tcp:8765'; then
      adb_cmd -s "$DEVICE" reverse --remove tcp:8765 2>/dev/null \
        && info "ADB tunnel removed" \
        || warn "Could not remove ADB tunnel for $DEVICE"
    fi
  fi
  info "iptables rules will be removed by the relay process on exit."
  info "Tap 'Disconnect' on the phone if the VPN is still active."
  echo ""
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Do not exec here: replacing the shell would discard its EXIT trap and
# leave the ADB reverse mapping behind. The relay handles its own networking
# cleanup; this shell removes the mapping when the relay exits.
RELAY_CMD=(./relay-bin)
if ! $USE_AOA; then
  if [[ "$ADB_BIN" != "adb" ]]; then
    RELAY_CMD+=(--adb-bin "$ADB_BIN")
  fi
  if [[ "$ADB_PORT" != "0" ]]; then
    RELAY_CMD+=(--adb-port "$ADB_PORT")
  fi
fi
RELAY_CMD+=("${RELAY_EXTRA_FLAGS[@]+"${RELAY_EXTRA_FLAGS[@]}"}")
"${RELAY_CMD[@]}"