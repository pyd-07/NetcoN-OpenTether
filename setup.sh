#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#   OpenTether (NetcoN) Setup Script - v0.9.3
#  | Tested on Debian / Ubuntu (amd64 + arm64)
#
#  Usage:
#    sudo ./setup.sh              # ADB transport  (default, USB debugging required)
#    sudo ./setup.sh --aoa        # AOA transport  (no USB debugging needed)
#    sudo ./setup.sh --build-only # build binaries only, don't start relay
#    sudo ./setup.sh -v           # verbose relay output
#    sudo ./setup.sh --help       # show this help
# ══════════════════════════════════════════════════════════════════════════════
export PATH=$PATH:/usr/local/go/bin:/home/${SUDO_USER:-root}/go/bin
set -euo pipefail

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${GREEN}[✔]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[✘]${NC} $1"; exit 1; }
section() { echo -e "\n${CYAN}${BOLD}── $1 ──${NC}"; }

# ── Help ──────────────────────────────────────────────────────────────────────
usage() {
  echo -e "${BOLD}OpenTether setup v0.9.3${NC}"
  echo ""
  echo "  sudo ./setup.sh              ADB transport (USB debugging required)"
  echo "  sudo ./setup.sh --aoa        AOA transport (no USB debugging needed)"
  echo "  sudo ./setup.sh --build-only Build relay + APK only, don't start"
  echo "  sudo ./setup.sh -v           Verbose relay logging"
  echo "  sudo ./setup.sh --help       Show this help"
  echo ""
  echo "Flags can be combined:  sudo ./setup.sh --aoa -v"
  exit 0
}

# ── Parse flags ───────────────────────────────────────────────────────────────
USE_AOA=false
BUILD_ONLY=false
RELAY_EXTRA_FLAGS=()

for arg in "$@"; do
  case "$arg" in
    --aoa)        USE_AOA=true ;;
    --build-only) BUILD_ONLY=true ;;
    --help|-h)    usage ;;
    *)            RELAY_EXTRA_FLAGS+=("$arg") ;;
  esac
done

# ── Banner ────────────────────────────────────────────────────────────────────
echo -e "${BOLD}"
echo "  ╔═══════════════════════════════╗"
echo "  ║   OpenTether  v0.9.3  setup   ║"
echo "  ╚═══════════════════════════════╝"
echo -e "${NC}"
echo "  Transport : $(if $USE_AOA; then echo 'AOA (direct USB, no USB debugging)'; else echo 'ADB (TCP over USB, USB debugging required)'; fi)"
echo "  Mode      : $(if $BUILD_ONLY; then echo 'build only'; else echo 'build + run'; fi)"
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
install_if_missing java      default-jdk

if ! $USE_AOA; then
  install_if_missing adb adb
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
GO_MIN="1.22"
need_go=false
if ! command -v go &>/dev/null; then
  need_go=true
  warn "Go not found"
else
  GO_CURRENT=$(go version | grep -oP '\d+\.\d+' | head -1)
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
  curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${GO_ARCH}.tar.gz" \
    | tar -C /usr/local -xz
  ln -sf /usr/local/go/bin/go /usr/local/bin/go
  info "Go ${GO_VERSION} installed"
fi

# ── 3. Build the relay ────────────────────────────────────────────────────────
section "Building relay"

if $USE_AOA; then
  info "Fetching gousb dependency..."
  GRADLE_USER="${SUDO_USER:-root}"
  if [[ "$GRADLE_USER" != "root" ]]; then
    sudo -u "$GRADLE_USER" env PATH="$PATH" go get github.com/google/gousb@v1.1.3 \
      || go get github.com/google/gousb@v1.1.3
  else
    go get github.com/google/gousb@v1.1.3
  fi
  go build -tags aoa -o relay . \
    || error "AOA relay build failed — check that libusb-1.0-0-dev is installed"
  info "relay (AOA) built → ./relay"
else
  go build -o relay . \
    || error "Relay build failed — run 'go build -o relay .' to see the full error"
  info "relay built → ./relay"
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
APK_NAME="opentether-v0.9.3.apk"
APK_URL="https://github.com/piyushyadav-pyd/NetcoN-OpenTether/releases/download/v0.9.3/$APK_NAME"
cp "$APK_SRC" "../$APK_NAME"
info "APK built → ./$APK_NAME"
cd ..

# ── 5. Build-only exit ────────────────────────────────────────────────────────
if $BUILD_ONLY; then
  echo ""
  info "Build complete."
  echo ""
  echo "  Relay binary  →  ./relay"
  echo "  Android APK   →  ./$APK_NAME"
  echo ""
  if ! $USE_AOA; then
    echo "  To run manually:"
    echo "    adb install $APK_NAME"
    echo "    sudo ./relay        # sets up adb reverse automatically"
  else
    echo "  To run manually:"
    echo "    adb install $APK_NAME     # one-time, needs USB debugging just for install"
    echo "    sudo ./relay              # connect cable — phone prompts to open app"
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
  adb start-server &>/dev/null || true
  DEVICE=$(adb devices 2>/dev/null | awk 'NR>1 && /\tdevice$/ {print $1; exit}')
  if [[ -z "$DEVICE" ]]; then
    echo ""
    error "No authorised Android device found.

  Checklist:
    1.  Cable is plugged in
    2.  Settings → Developer Options → USB Debugging is ON
    3.  You tapped 'Allow' on the USB debugging prompt on the phone
    4.  Running 'adb devices' shows your device as 'device' (not 'unauthorized')

  Once fixed, re-run:  sudo ./setup.sh"
  fi
  info "Device found: $DEVICE"
fi

# ── 7. Install APK ────────────────────────────────────────────────────────────
section "Installing app on device"

if ! $USE_AOA; then
  adb install -r "$APK_NAME" \
    && info "App installed on $DEVICE" \
    || error "ADB install failed — try manually:  adb install -r $APK_NAME"
else
  warn "AOA mode — you need ADB once to install the APK (USB debugging required just for this):"
  warn "  adb install -r $APK_NAME"
  warn "After installation, USB debugging can be disabled."
fi

# ── 8. ADB reverse tunnel (ADB transport only) ────────────────────────────────
if ! $USE_AOA; then
  section "Setting up ADB reverse tunnel"
  # The relay watches for devices and re-runs this on reconnect automatically,
  # but running it now means the very first connection works immediately.
  adb reverse tcp:8765 tcp:8765 \
    && info "ADB reverse tunnel active  (phone:8765 → PC:8765)" \
    || warn "adb reverse failed — relay will retry automatically when phone reconnects"
fi

# ── 9. Launch app ─────────────────────────────────────────────────────────────
section "Launching app"

if ! $USE_AOA; then
  adb shell am start -n com.opentether/.MainActivity &>/dev/null \
    && info "OpenTether launched on device" \
    || warn "Auto-launch failed — open OpenTether manually on the phone"
  echo ""
  warn "Tap 'Start VPN' and accept the system VPN consent dialog on first use."
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
  if ! $USE_AOA; then
    adb reverse --remove tcp:8765 2>/dev/null && info "ADB tunnel removed" || true
  fi
  info "iptables rules will be removed by the relay process on exit."
  info "Tap 'Stop VPN' on the phone if still active."
  echo ""
}
trap cleanup EXIT INT TERM

# exec replaces this shell so Ctrl+C hits the relay directly,
# guaranteeing its iptables cleanup runs before our trap above.
exec ./relay "${RELAY_EXTRA_FLAGS[@]+"${RELAY_EXTRA_FLAGS[@]}"}"