# NetcoN OpenTether

[![Release](https://img.shields.io/github/v/release/pyd-07/NetcoN-OpenTether?include_prereleases)](https://github.com/pyd-07/NetcoN-OpenTether/releases)
[![CI](https://github.com/pyd-07/NetcoN-OpenTether/actions/workflows/ci.yml/badge.svg)](https://github.com/pyd-07/NetcoN-OpenTether/actions/workflows/ci.yml)

Reverse USB tethering for Android: route your Android device's traffic through a Linux PC's Internet connection over USB, without root on the phone.

> **Current release:** `v0.9.5-beta.1` — Android compatibility preview. Real-device validation is still in progress. ADB is the recommended beta test path; AOA remains experimental while its release transport selection is being validated.

---

## For users

### Requirements

- Android 8.0+ (API 26+)
- A USB data cable
- A Linux PC with Internet access
- `adb` for ADB transport, or USB/libusb access for AOA
- `iptables`, `ip6tables`, and `iproute2` on the Linux PC

### Install the beta

The beta release automatically publishes an Android APK and Linux relay executables on GitHub Releases.

Open the latest prerelease:

<https://github.com/pyd-07/NetcoN-OpenTether/releases>

Release assets include:

- `OpenTether-v0.9.5-beta.1-android.apk`
- `OpenTether-v0.9.5-beta.1-linux-amd64`
- `OpenTether-v0.9.5-beta.1-linux-arm64`
- `OpenTether-v0.9.5-beta.1-linux-amd64-aoa`
- `OpenTether-v0.9.5-beta.1-SHA256SUMS.txt`

No binaries are committed to the repository; GitHub Actions builds and attaches them to releases.

### ADB mode — recommended

ADB is the simplest way to validate the beta.

1. Enable **USB debugging** in Android Developer options.
2. Connect the phone by USB.
3. Confirm the Linux PC sees the device:

```bash
adb devices
```

The device should appear as `device`, not `unauthorized`.

Install the APK from the release asset or a local build:

```bash
adb install -r /path/to/OpenTether-v0.9.5-beta.1-android.apk
```

The relay normally configures `adb reverse tcp:8765 tcp:8765` itself. If you run
it with `--no-adb-watch`, configure the reverse mapping in the user ADB context
and verify it:

```bash
adb reverse tcp:8765 tcp:8765
adb reverse --list
```

4. Start the relay as root:

```bash
sudo ./OpenTether-v0.9.5-beta.1-linux-amd64
```

5. Open OpenTether on Android.
6. Grant the Android VPN permission.
7. Tap **Start VPN**.
8. Verify that Internet traffic works through the PC.

Keep the relay terminal open while the tunnel is active. The relay needs permission to create the TUN interface and configure routing/NAT.

For the full staged procedure, expected log observations, fault isolation, and
sanitized evidence bundle, see
[`docs/adb-integration.md`](docs/adb-integration.md). That guide contains manual,
privileged, and physical-device steps; it is not an automated test. A separate
manual ADB run on 2026-10-20 documented the reverse/control path, VPN/TUN data
path, and cleanup, while deliberate traffic and recovery stages remain
outstanding.

### AOA mode — experimental

AOA (Android Open Accessory) is intended to provide direct USB transport without requiring persistent USB debugging. The current beta AOA release path is still being validated, so prefer ADB for baseline testing.

A true AOA session should involve Android USB accessory negotiation and the OpenTether accessory permission flow. A release binary that starts an ADB watcher instead is not using the intended AOA transport.

For a source build, AOA mode must be selected explicitly; the `-tags aoa` build alone does not enable it:

```bash
sudo ./relay-bin --aoa
```

> **Accessory-version compatibility:** the current relay sends accessory version
> `1`, matching the current `usb_accessory_filter.xml`. APKs built before the
> filter changed from `1.0` to `1` will not match. Rebuild the APK; one relay
> cannot satisfy both version strings.

### Linux setup script

For a source checkout, the repository still provides the setup helper:

```bash
git clone https://github.com/pyd-07/NetcoN-OpenTether.git
cd NetcoN-OpenTether
sudo ./setup.sh
```

AOA setup:

```bash
sudo ./setup.sh --aoa
```

The setup script installs required host dependencies, builds the relay and Android app, and can configure a complete local installation. For beta testing, the prebuilt release assets are usually simpler.

---

## WSL2 testing

WSL2 can run the Linux relay, but USB devices are not automatically exposed to the Linux environment.

On Windows, install [`usbipd-win`](https://github.com/dorssel/usbipd-win), then inspect connected devices:

```powershell
usbipd list
```

Find the Android device and attach it to WSL:

```powershell
usbipd bind --busid <BUSID>
usbipd attach --wsl --busid <BUSID>
```

Then in WSL:

```bash
lsusb
adb devices
```

For ADB testing, Android USB debugging must also be enabled and authorized on the phone.

For AOA testing, WSL needs direct USB access because the relay communicates with the USB device rather than only through `adbd`.

> A GitHub Codespace is a separate remote machine. It is useful for builds, CI reproduction, and development, but it is not a substitute for a local WSL/Linux host when testing a physical Android USB device.

### Common WSL relay errors

**`TUNSETIFF ioctl: operation not permitted`**

Run the relay with the privileges required to create/configure the TUN device:

```bash
sudo ./OpenTether-v0.9.5-beta.1-linux-amd64
```

**`iptables: executable file not found in $PATH`**

Install the host dependency:

```bash
sudo apt update
sudo apt install -y iptables iproute2
```

---

## v0.9.5 beta status

`v0.9.5` is being developed as an Android compatibility release in three phases.

### Phase 1 — Fixed

- VPN service behavior after screen lock.
- VPN service behavior after process recreation.
- Foreground-service startup failures.
- VPN shutdown after system service recreation.
- USB permission handling across Android versions.
- AOA permission handling on newer Android releases.
- Activity/service lifecycle race conditions.

### Phase 2 — Added

- Android API-level compatibility handling.
- Android lifecycle-aware VPN management.
- Better foreground-service handling.
- Device information detection.
- Battery optimization diagnostics.
- Android-specific troubleshooting information.
- Diagnostics UI and compatibility checks.

### Phase 3 — Improved

Planned reliability and usability improvements build on the fixed and added compatibility work before the stable `v0.9.5` release.

### Beta testing focus

For `v0.9.5-beta.1`, please prioritize:

- Android 8/9 compatibility.
- Android 10/11 compatibility.
- Android 12/13 behavior.
- Android 14+ foreground-service behavior.
- Screen lock → unlock.
- Service/process recreation.
- USB disconnect → reconnect.
- ADB permission handling.
- Battery optimization behavior.
- OEM-specific background restrictions.

### Validation status

Automated checks cover the Go relay's configuration validation, short-write
handling, session cancellation, protocol framing boundaries, AOA string/filter
parity, the matching Kotlin protocol codec, deterministic Android-to-TUN and
TUN-to-Android session paths, ADB device-state parsing/backoff, and the Android
startup-error lifecycle state. The current local remediation was verified with:

```bash
go test -race -count=1 -timeout=120s ./...
go vet ./...
make fmt-check
make test-integration
make shellcheck
bash -n setup.sh
( cd android-client && ./gradlew testDebugUnitTest --no-daemon )
git diff --check
```

The session tests use pipes and a TUN write seam; they do not open `/dev/net/tun`,
invoke `adb`, or send traffic. They are mock integration tests, not real-device
validation.

Standard relay compilation is verified. AOA compilation requires
`libusb-1.0-0-dev` and is not verified in every development environment.

A manual ADB device run on 2026-10-20 established the reverse transport, the
app-owned relay control session, PING/PONG, the VPN/TUN data path, and cleanup
that restored the host networking baseline. Real background traffic was observed
after the VPN was started manually; that traffic is expected during an active
tunnel. Still outstanding: AOA accessory negotiation and permission prompts,
real USB bulk transfer behavior, deliberate IPv4/DNS/HTTPS and large-transfer
traffic, IPv6 forwarding, reconnect behavior, and full end-to-end connectivity
validation on physical Android devices. ADB remains the recommended path while
those checks are completed. See
[`docs/adb-integration.md`](docs/adb-integration.md) for the staged procedure.

---

## Developer workflow

### GitHub Codespaces / Dev Container

The repository includes a development container for reproducible builds and checks. It provides the Android SDK environment together with Go, `make`, `shellcheck`, and libusb development dependencies.

Start with:

```bash
make doctor
```

Common commands:

```bash
make fmt
make fmt-check
make test
make test-go
make test-integration
make test-android
make lint
make build
make build-aoa
make shellcheck
make check
make clean
```

`make check` is intended to approximate the repository's local validation. GitHub Actions remains the authoritative CI environment.

### Build from source

Android:

```bash
cd android-client
./gradlew assembleDebug
```

Standard relay:

```bash
go build -o relay-bin .
```

AOA-enabled relay:

```bash
sudo apt install libusb-1.0-0-dev
go build -tags aoa -o relay-bin .
```

### Release artifacts

Version tags such as `v0.9.5-beta.1` trigger the release workflow. The workflow builds:

- Android debug APK
- Linux amd64 relay
- Linux arm64 relay
- Linux amd64 AOA relay
- SHA-256 checksums

The release workflow creates a prerelease automatically for alpha, beta, and release-candidate tags.

---

## Architecture

```text
Android App
    │
    ▼
VpnService / TUN
    │
    ├── ADB transport ── adb reverse ──┐
    │                                  │
    └── AOA transport ── USB ──────────┤
                                       ▼
                                  Go relay
                                       │
                                  Host TUN ot0
                                       │
                              iptables / ip6tables NAT
                                       │
                                    Internet
```

The Android side captures packets with `VpnService`. The Linux relay terminates the transport, forwards packets through a host TUN interface, and applies IPv4/IPv6 forwarding and NAT rules.

The relay also owns transport detection, reconnect behavior, connection state, and cleanup. Recent stability work added bounded reconnect behavior and explicit lifecycle states so transport failures can recover without requiring a full application restart.

### ADB transport

ADB mode uses `adb reverse` to expose the relay service through the authorized Android debugging channel.

### AOA transport

AOA is designed around Android Open Accessory USB bulk transfers. The Android side uses USB accessory permissions and the Linux relay uses libusb/gousb to communicate with the accessory.

The AOA path has previously required strict framing discipline: one OTP frame per USB bulk transfer on the sender side and buffered reads on the Android receiver to avoid losing unread transfer data.

### OTP protocol

Every packet is wrapped in the OpenTether Protocol (OTP). The fixed header is 12 bytes, big-endian:

```text
 0       4       8  9  10      12
 |conn_id|pay_len|ty|fl|reservd|...payload...
```

- `conn_id`: logical connection identifier
- `pay_len`: payload length; zero-length control frames are valid
- `ty`: message type
- `fl`: flags
- `reserved`: reserved and expected to be zero

### Tunnel configuration constraints

The relay validates its configuration before creating a TUN device or changing
host networking:

- `--listen` must be an explicit loopback IP literal (`127.0.0.1:8765` or
  `[::1]:8765`) in ADB/TCP mode. Empty listeners, wildcard addresses,
  hostnames, LAN addresses, and port 0 are rejected. AOA mode does not create a
  TCP listener, so the listener may be empty there.
- `--tun-prefix` must be `/24` through `/30`. The stock Android client uses
  `10.0.0.1/24`; the relay default is `10.0.0.2/24`.
- `--mtu` must be `1280` through `1400`. The lower bound is the IPv6 minimum
  link MTU (the client routes IPv6); the upper bound matches the stock client's
  fixed 1400-byte VPN MTU.
- `--tun-addr` and `--android-ip` must be distinct IPv4 addresses in the same
  tunnel subnet and must not be the subnet's network or broadcast address.
  Unspecified, loopback, multicast, link-local, broadcast, IPv6, and IPv4-mapped
  addresses are rejected.

The stock Android client is not runtime-configurable: it connects to
`127.0.0.1:8765`, assigns itself `10.0.0.1/24`, and uses a 1400-byte MTU. The
relay defaults (`127.0.0.1:8765`, `--android-ip 10.0.0.1`,
`--tun-addr 10.0.0.2`, `--tun-prefix 24`, `--mtu 1400`) match those values.
Changing the listener host/port, `--android-ip`, or the tunnel subnet requires a
matching Android client build; if you change the listener port, the
`adb reverse` target must use that port too. `127.0.0.1:8765` is the reliable
stock-ADB path; other loopback literals are accepted for custom setups.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| `adb devices` is empty | Confirm USB debugging, Windows/WSL USB access, and that the phone is attached to the WSL environment. |
| Device is `unauthorized` | Unlock the phone and accept the USB debugging authorization prompt. |
| `TUNSETIFF ioctl: operation not permitted` | Run the relay with `sudo`. |
| `iptables` or `ip6tables` not found | Install `iptables` and `iproute2`; the relay configures both IPv4 and IPv6 NAT. |
| `adb reverse --list` does not show `tcp:8765` | Verify the reverse mapping in the same ADB context as the authorized device, or run the relay with `--no-adb-watch` and configure it manually. |
| Relay is ready but no Android session connects | Check that the app is running, the VPN permission was granted, and `adb reverse` points at the relay listener; inspect both relay and `OT/UsbTunnelClient` logs. |
| VPN stops after screen lock | Check battery optimization and OEM background restrictions in the Diagnostics screen. |
| VPN starts but traffic does not flow | Test an IPv4 literal first, then a hostname to separate tunnel/route/NAT failures from DNS failures; inspect relay and app logs for session errors. |
| IPv4 works but IPv6 fails | Treat IPv6 as a separate result; check `ip6tables`, host IPv6 forwarding/upstream support, and the `ot0` address. |
| AOA binary starts an ADB watcher | Treat the AOA release path as experimental; use ADB for baseline beta validation and report the relay output. |

For bug reports, include Android version/API, device model, transport mode, relay output, and relevant `adb logcat` output when available.

---

## Project roadmap

See [`ROADMAP.md`](ROADMAP.md) for the current release plan.

The near-term sequence is:

```text
v0.9.4 Stability
      ↓
v0.9.5 Android Compatibility
      ├── Fixed
      ├── Added
      └── Improved
      ↓
v0.9.6 Network Reliability
      ↓
v0.9.7 Linux Compatibility
      ↓
v0.9.8 Diagnostics + Security
      ↓
v1.0.0-beta.1
```

---

## Contributing

Issues and pull requests are welcome. Keep changes focused and easy to review. Large behavioral changes should be split into separate modules or pull requests where practical.

---

License: Apache 2.0
