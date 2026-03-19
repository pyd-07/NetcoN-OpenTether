#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  OpenTether — one-command setup
#  v0.9.1 | Tested on Debian / Ubuntu
#
#  Usage:
#    sudo ./setup.sh              # ADB transport (default)
#    sudo ./setup.sh --aoa        # AOA transport (no USB debugging needed)
#    sudo ./setup.sh -v           # pass -v straight to the relay (verbose)
# ══════════════════════════════════════════════════════════════════════════════
export PATH=$PATH:/usr/local/go/bin:/home/$SUDO_USER/go/bin
set -euo pipefail

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${GREEN}[✔]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[✘]${NC} $1"; exit 1; }
section() { echo -e "\n${CYAN}${BOLD}── $1 ──${NC}"; }

# ── Parse flags ───────────────────────────────────────────────────────────────
USE_AOA=false
RELAY_EXTRA_FLAGS=()

for arg in "$@"; do
  case "$arg" in
    --aoa) USE_AOA=true ;;
    *)     RELAY_EXTRA_FLAGS+=("$arg") ;;
  esac
done

# ── Banner ────────────────────────────────────────────────────────────────────
echo -e "${BOLD}"
echo "  ╔═══════════════════════════════╗"
echo "  ║   OpenTether  v0.9.1  setup   ║"
echo "  ╚═══════════════════════════════╝"
echo -e "${NC}"

if $USE_AOA; then
  warn "AOA mode enabled — USB debugging is NOT required on the phone"
  warn "The relay will negotiate AOA directly; phone will prompt once to open app"
fi

# ── 1. Root check ─────────────────────────────────────────────────────────────
section "Checking permissions"
[[ $EUID -eq 0 ]] || error "Run with sudo:  sudo ./setup.sh"
info "Running as root"

# ── 2. Dependencies ───────────────────────────────────────────────────────────
section "Installing dependencies"

apt-get update -qq

install_if_missing() {
  local cmd=$1 pkg=${2:-$1}
  if ! command -v "$cmd" &>/dev/null; then
    warn "$cmd not found — installing $pkg..."
    apt-get install -y "$pkg" &>/dev/null
    info "$cmd installed"
  else
    info "$cmd already present"
  fi
}

install_if_missing iptables  iptables
install_if_missing ip        iproute2
install_if_missing java      default-jdk

# ADB — only required for the ADB transport
if ! $USE_AOA; then
  install_if_missing adb adb
fi

# libusb — only required for the AOA transport
if $USE_AOA; then
  if ! dpkg -s libusb-1.0-0-dev &>/dev/null 2>&1; then
    warn "libusb-1.0-0-dev not found — installing..."
    apt-get install -y libusb-1.0-0-dev &>/dev/null
    info "libusb installed"
  else
    info "libusb already present"
  fi
fi

# Go — apt version is often outdated, install from upstream if needed
if ! command -v go &>/dev/null; then
  warn "Go not found — installing latest stable release..."
  GO_VERSION=$(curl -fsSL "https://go.dev/VERSION?m=text" | head -1 | sed 's/go//')
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
  info "Go $(go version | awk '{print $3}' | sed 's/go//') already present"
fi

# ── 3. Build the relay ────────────────────────────────────────────────────────
section "Building relay"

if $USE_AOA; then
  # Pull gousb only when we need it — keeps the default build dependency-free
  info "Fetching gousb for AOA support..."
  sudo -u "$SUDO_USER" go get github.com/google/gousb@v1.1.3 2>/dev/null || \
    go get github.com/google/gousb@v1.1.3
  go build -tags aoa -o relay . || error "Relay build (AOA) failed"
  info "Relay built with AOA support"
else
  go build -o relay . || error "Relay build failed"
  info "Relay built (ADB transport)"
fi

# ── 4. Phone connection check ─────────────────────────────────────────────────
section "Checking phone connection"

if $USE_AOA; then
  # AOA doesn't need USB debugging — just check a USB device is present
  if command -v lsusb &>/dev/null; then
    if lsusb | grep -qi "android\|google\|samsung\|xiaomi\|huawei\|motorola"; then
      info "Android device detected via USB"
    else
      warn "No Android device detected — connect your phone over USB and continue"
      warn "USB debugging does NOT need to be enabled for AOA mode"
    fi
  else
    warn "lsusb not available — skipping device detection (install usbutils to enable)"
  fi
else
  # ADB transport — need USB debugging
  adb start-server &>/dev/null
  DEVICE=$(adb devices 2>/dev/null | awk 'NR>1 && /\tdevice$/ {print $1; exit}')
  if [[ -z "$DEVICE" ]]; then
    error "No authorised Android device found.\n\
  Make sure:\n\
    1. USB debugging is enabled  (Settings → Developer options → USB debugging)\n\
    2. You have accepted the USB debugging authorisation prompt on the phone\n\
    3. The cable is properly connected\n\
  Then re-run setup.sh"
  fi
  info "Device found: $DEVICE"
fi

# ── 5. Build and install Android app ─────────────────────────────────────────
section "Building Android app"

cd android-client
chmod +x gradlew

# Run Gradle as the original user so ~/.gradle is in the right place
sudo -u "$SUDO_USER" ./gradlew assembleDebug --quiet \
  || ./gradlew assembleDebug --quiet \
  || error "Android build failed — make sure JDK 17+ is installed"

info "APK built"

if ! $USE_AOA; then
  info "Installing app on device..."
  adb install -r app/build/outputs/apk/debug/app-debug.apk \
    || error "ADB install failed — check cable and USB debugging"
  info "App installed"
else
  warn "AOA mode: install the APK manually before connecting the cable:"
  warn "  adb install -r android-client/app/build/outputs/apk/debug/app-debug.apk"
  warn "  (requires USB debugging just for this one install step)"
fi

cd ..

# ── 6. ADB reverse tunnel (ADB transport only) ────────────────────────────────
if ! $USE_AOA; then
  section "ADB reverse tunnel"
  # The relay watches adb devices and re-runs this automatically,
  # but we set it up here so it's ready before the relay starts.
  adb reverse tcp:8765 tcp:8765 \
    && info "ADB tunnel ready (phone:8765 → PC:8765)" \
    || warn "adb reverse failed — relay will retry automatically when phone reconnects"
fi

# ── 7. Launch app on phone ────────────────────────────────────────────────────
section "Launching app"

if ! $USE_AOA; then
  adb shell am start -n com.opentether/.MainActivity &>/dev/null \
    && info "OpenTether launched on device" \
    || warn "Could not launch app via ADB — open it manually"
  warn "Tap 'Start VPN' on your phone. Accept the VPN consent dialog if prompted."
else
  warn "Connect your phone over USB."
  warn "The phone will show 'Open OpenTether for this accessory?' — tap OK once."
  warn "After that the app opens automatically every time you plug in the cable."
fi

# ── 8. Start relay ────────────────────────────────────────────────────────────
section "Starting relay"
echo ""

if $USE_AOA; then
  info "Relay will negotiate AOA and wait for phone — connect cable now"
else
  info "Relay is watching for device connections (auto adb reverse enabled)"
fi

echo ""
info "Press Ctrl+C to stop"
echo ""

cleanup() {
  echo ""
  section "Shutting down"
  if ! $USE_AOA; then
    adb reverse --remove tcp:8765 2>/dev/null && info "ADB tunnel removed" || true
  fi
  info "Relay stopping — iptables rules will be cleaned up by the relay process"
  info "Tap 'Stop VPN' on your phone if not already stopped."
}
trap cleanup EXIT INT TERM

if $USE_AOA; then
  exec ./relay -tags-aoa "${RELAY_EXTRA_FLAGS[@]+"${RELAY_EXTRA_FLAGS[@]}"}"
else
  exec ./relay "${RELAY_EXTRA_FLAGS[@]+"${RELAY_EXTRA_FLAGS[@]}"}"
fi