#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────
#  OpenTether — one-command setup
#  Tested on Debian/Ubuntu
# ─────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info()    { echo -e "${GREEN}[✔]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[✘]${NC} $1"; exit 1; }

# ─────────────────────────────────────────────
#  1. Root check
# ─────────────────────────────────────────────
if [[ $EUID -ne 0 ]]; then
  error "Run this script with sudo: sudo ./setup.sh"
fi

# ─────────────────────────────────────────────
#  2. Check and install dependencies
# ─────────────────────────────────────────────
install_if_missing() {
  local cmd=$1
  local pkg=${2:-$1}
  if ! command -v "$cmd" &>/dev/null; then
    warn "$cmd not found — installing..."
    apt-get install -y "$pkg" &>/dev/null
    info "$cmd installed"
  else
    info "$cmd already installed"
  fi
}

apt-get update -qq

install_if_missing adb adb
install_if_missing iptables iptables
install_if_missing java default-jdk

# Go needs special handling — apt version is often outdated
if ! command -v go &>/dev/null; then
  warn "Go not found — installing Go 1.22..."
  GO_VERSION="1.22.3"
  ARCH=$(dpkg --print-architecture)
  case $ARCH in
    amd64) GO_ARCH="amd64" ;;
    arm64) GO_ARCH="arm64" ;;
    *)     error "Unsupported architecture: $ARCH" ;;
  esac
  curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" \
    | tar -C /usr/local -xz
  ln -sf /usr/local/go/bin/go /usr/local/bin/go
  info "Go ${GO_VERSION} installed"
else
  GO_VER=$(go version | awk '{print $3}' | sed 's/go//')
  info "Go $GO_VER already installed"
fi

# ─────────────────────────────────────────────
#  3. Build the relay
# ─────────────────────────────────────────────
info "Building relay..."
go build -o relay ./... || error "Relay build failed"
info "Relay built successfully"

# ─────────────────────────────────────────────
#  4. Check phone is connected
# ─────────────────────────────────────────────
info "Checking for connected Android device..."

# Give ADB server a moment to start
adb start-server &>/dev/null

DEVICE=$(adb devices | awk 'NR>1 && /device$/ {print $1; exit}')

if [[ -z "$DEVICE" ]]; then
  error "No Android device found.\n  Make sure:\n  1. USB debugging is enabled on your phone\n  2. You have accepted the USB debugging prompt on the phone\n  3. The cable is properly connected"
fi

info "Device found: $DEVICE"

# ─────────────────────────────────────────────
#  5. Build and install the Android app
# ─────────────────────────────────────────────
info "Building Android app..."
cd android-client
chmod +x gradlew
./gradlew assembleDebug --quiet || error "Android build failed — check JDK version (needs 17+)"
info "Android app built"

info "Installing app on device..."
adb install -r app/build/outputs/apk/debug/app-debug.apk \
  || error "ADB install failed"
info "App installed"

cd ..

# ─────────────────────────────────────────────
#  6. Set up ADB reverse tunnel
# ─────────────────────────────────────────────
info "Setting up ADB reverse tunnel..."
adb reverse tcp:8765 tcp:8765 || error "Failed to set up ADB reverse tunnel"
info "ADB tunnel ready (phone:8765 → PC:8765)"

# ─────────────────────────────────────────────
#  7. Launch the app on the phone
# ─────────────────────────────────────────────
info "Launching OpenTether on device..."
adb shell am start -n com.opentether/.MainActivity &>/dev/null
warn "Tap 'Start VPN' on your phone. Accept the VPN consent dialog if prompted."

# ─────────────────────────────────────────────
#  8. Start the relay (blocking)
# ─────────────────────────────────────────────
echo ""
info "Starting relay... (Ctrl+C to stop)"
echo ""

cleanup() {
  echo ""
  info "Stopping — removing ADB tunnel and iptables rules..."
  adb reverse --remove tcp:8765 2>/dev/null || true
  info "Done. Tap 'Stop VPN' on your phone if not already stopped."
}
trap cleanup EXIT INT TERM

./relay "$@"