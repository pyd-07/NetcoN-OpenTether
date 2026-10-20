# NetcoN OpenTether

[![Release](https://img.shields.io/github/v/release/pyd-07/NetcoN-OpenTether?include_prereleases)](https://github.com/pyd-07/NetcoN-OpenTether/releases)
[![CI](https://github.com/pyd-07/NetcoN-OpenTether/actions/workflows/ci.yml/badge.svg)](https://github.com/pyd-07/NetcoN-OpenTether/actions/workflows/ci.yml)

Reverse USB tethering for Android: route your Android device's traffic through a Linux PC's Internet connection over USB, without root on the phone.

> **Release status:** OpenTether is in the `v0.9.5` beta series. Use [GitHub Releases](https://github.com/pyd-07/NetcoN-OpenTether/releases) for the latest published prerelease and its matching assets. The project is still undergoing real-device validation. ADB is the recommended test path; AOA remains experimental.

---

## For users

### Requirements

- Android 8.0+ (API 26+)
- A USB data cable
- A Linux PC with Internet access
- ADB for the recommended transport, or USB/libusb access for experimental AOA
- `iptables`, `ip6tables`, and `iproute2` on the Linux PC

### Install a beta release

The release workflow publishes an Android APK, Linux relay executables, and SHA-256 checksums.

Open [GitHub Releases](https://github.com/pyd-07/NetcoN-OpenTether/releases) and download all required assets from the **same release tag**. Asset names follow this pattern:

- `OpenTether-<tag>-android.apk`
- `OpenTether-<tag>-linux-amd64`
- `OpenTether-<tag>-linux-arm64`
- `OpenTether-<tag>-linux-amd64-aoa`
- `OpenTether-<tag>-SHA256SUMS.txt`

The release workflow currently builds a **debug APK** intended for beta testing, not a production-signed Android application. No generated binaries are committed to the repository; GitHub Actions builds and attaches release assets.

### ADB mode — recommended

1. Enable **USB debugging** in Android Developer options.
2. Connect the phone by USB and authorize the host fingerprint.
3. Confirm the ADB client used for testing can see the device:

   ```bash
   adb devices
   ```

   The device should appear as `device`, not `unauthorized` or `offline`.

4. Install the APK downloaded from that same release:

   ```bash
   adb install -r /path/to/OpenTether-<tag>-android.apk
   ```

5. Start the matching Linux relay as root:

   ```bash
   sudo ./OpenTether-<tag>-linux-amd64
   ```

6. Open OpenTether on Android, grant VPN permission if prompted, and tap **Connect** on Home.
7. Verify actual connectivity through the Linux host. A successful build, a visible ADB device, or a reverse mapping alone does not prove that Internet traffic works.

The relay normally configures `adb reverse tcp:8765 tcp:8765` automatically. If you use `--no-adb-watch`, configure and verify the mapping in the same ADB context that owns the device:

```bash
adb reverse tcp:8765 tcp:8765
adb reverse --list
```

Keep the relay terminal open while the tunnel is active. The relay requires privileges to create the TUN interface and configure host routing/NAT. Review the networking and cleanup cautions in [the ADB integration validation guide](docs/adb-integration.md) before running a privileged relay.

The Android UI is organized around **Home** and **Docs**. Home presents the connection state and primary action. Docs contains setup, connection lifecycle, troubleshooting, technical reference, privacy, and developer notes. Transport, accessory auto-start, and DNS override options are available under **Connection settings**.

The [ADB integration validation guide](docs/adb-integration.md) describes the staged procedure, expected observations, fault isolation, and evidence sanitization. It clearly distinguishes automated checks from physical-device validation.

### Windows + WSL2 ADB configuration

In WSL2, the Linux ADB server may not see a phone connected to Windows. If Windows `adb.exe` can see the device but Linux `adb devices` is empty, configure the relay watcher to use the Windows ADB executable and the server port that owns the device.

Example (replace the path with the actual location on your machine):

```bash
sudo ./relay-bin \
  --adb-bin "/mnt/c/Users/<user>/.../platform-tools/adb.exe" \
  --adb-port 5038
```

- `--adb-bin` selects the ADB executable used by automatic reverse setup. The default is `adb` from `PATH`.
- `--adb-port` selects the ADB server port. The default `0` preserves the ADB client's default-port behavior; set a nonzero value such as `5038` when that is the server that sees your phone.

Use a real executable path for your Windows installation. Do not copy the illustrative path literally. Check device visibility first with the same executable and port:

```bash
"/path/to/adb.exe" -P 5038 devices
"/path/to/adb.exe" -P 5038 reverse --list
```

An empty device list on one ADB server does not mean the phone is disconnected from every ADB server. Always inspect the server that owns the device transport.

### AOA mode — experimental

AOA (Android Open Accessory) is intended to provide direct USB transport without persistent USB debugging. Its release path and physical-device behavior are still being validated, so use ADB for baseline testing.

For a source build, AOA mode must be explicitly selected; building with `-tags aoa` alone does not turn it on:

```bash
sudo ./relay-bin --aoa
```

AOA requires USB access and libusb development files (for Debian/Ubuntu, `libusb-1.0-0-dev`). A true AOA session should perform Android USB accessory negotiation and use the OpenTether accessory permission flow. Treat a binary that starts an ADB watcher as not using the intended AOA transport.

> **Accessory-version compatibility:** the relay currently sends accessory version `1`, matching the current `usb_accessory_filter.xml`. APKs built against the older `1.0` filter will not match; rebuild the APK so the version strings agree.

### Linux setup script

For a source checkout:

```bash
git clone https://github.com/pyd-07/NetcoN-OpenTether.git
cd NetcoN-OpenTether
sudo ./setup.sh
```

For the AOA setup path:

```bash
sudo ./setup.sh --aoa
```

The setup script installs host dependencies and builds/configures a local installation. It may change host configuration; review it and its cleanup behavior before running it. Prebuilt release assets are usually simpler for beta testing.

> **Networking caution:** the relay runs with elevated privileges, creates a TUN interface, and configures routes and NAT/firewall rules. Use it only on a host you are authorized to reconfigure, keep the listener loopback-only, and follow the documented cleanup audit after testing.

---

## Current beta status

The `v0.9.5` beta series combines relay hardening, Android lifecycle/session-startup improvements, and a connection-first UI.

### Recent changes

- **Relay validation and transport safety:** validate tunnel/listener configuration before changing host networking, detect short writes across transport and buffered paths, tighten session cancellation/cleanup, and improve AOA string/filter compatibility.
- **ADB/session startup:** distinguish fatal startup errors from runtime reconnect errors, extract deterministic device-state parsing and bounded reverse-setup backoff, and expand relay and Android lifecycle tests.
- **Connection-first Android UI:** reduce the main navigation to Home and Docs, show explicit connection states and the relevant primary action, add connection settings and troubleshooting guidance, and remove obsolete screens/components.
- **Configurable ADB for WSL:** add `--adb-bin` and `--adb-port` so automatic reverse mapping can target a non-default ADB server, with diagnostics for repeated empty device discovery and focused watcher/configuration tests.

These are implementation changes, not a claim that every device, host, or transport combination has passed physical validation.

### Validation status

The repository includes automated Go tests, race-enabled tests, vet/lint checks, deterministic relay session tests, Android unit tests, and release CI. The ADB integration guide includes the current staged commands and the limits of each validation layer.

Automated and mock integration tests do **not** open a real TUN device, configure host routing/NAT, perform USB accessory negotiation, or prove end-to-end Internet connectivity. Passing them does not establish that the VPN works on every Android device.

A manual ADB run on 2026-10-20 established the reverse/control path, the app-owned relay session, PING/PONG, the VPN/TUN data path, and cleanup that restored the host networking baseline. Traffic was observed after the VPN had been started manually. That run did not complete the full validation matrix.

Still outstanding or requiring additional device/host coverage:

- Deliberate IPv4, DNS, HTTPS, and large-transfer tests
- IPv6 forwarding and upstream compatibility
- Disconnect/reconnect and relay-restart recovery under test
- Broad Android-version and OEM coverage
- AOA accessory negotiation, permission prompts, and real USB bulk transfers
- Full end-to-end connectivity validation across supported Linux hosts

ADB remains the recommended baseline until the outstanding cases have been verified. See [`docs/adb-integration.md`](docs/adb-integration.md) for the staged procedure and evidence requirements.

---

## Developer workflow

### Codespaces / dev container

The repository includes a development container with the tools needed for reproducible builds and checks. Begin with:

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

`make check` is intended to approximate local validation. GitHub Actions is the authoritative CI environment.

### Build from source

Android debug APK:

```bash
cd android-client
./gradlew assembleDebug
```

Standard relay:

```bash
go build -o relay-bin .
```

AOA-enabled relay (requires libusb development files):

```bash
go build -tags aoa -o relay-bin .
```

### Release artifacts

Pushing a version tag matching `v*` triggers the release workflow. For example, tags such as `v0.9.5-beta.3` cause the workflow to:

1. Run Go tests and build the standard and AOA relay variants.
2. Build the Android debug APK.
3. Build Linux amd64 and arm64 relay executables.
4. Generate SHA-256 checksums.
5. Create a GitHub Release with generated release notes.

Tags containing `-alpha`, `-beta`, or `-rc` are marked as prereleases. Check the published release page for the latest tag and its actual assets; do not assume a version number from this README is the newest published one.

---

## Architecture

```text
Android app
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
                                    Host TUN
                                       │
                              IPv4/IPv6 forwarding + NAT
                                       │
                                    Internet
```

The Android side captures packets with `VpnService`. The Linux relay terminates the selected transport, forwards packets through a host TUN interface, and applies host forwarding/NAT rules.

### ADB transport

ADB mode uses `adb reverse` to expose the relay service through the authorized Android debugging channel. The watcher supports explicit ADB executable and server-port configuration. Defaults preserve standard `adb` behavior; in WSL, select the Windows ADB executable and the server port that actually sees the phone.

### AOA transport

AOA is designed around Android Open Accessory USB bulk transfers. The Android side uses USB accessory permissions and the Linux relay uses libusb/gousb. This path remains experimental and requires physical-device testing.

### OpenTether Protocol (OTP)

Packets are wrapped in OTP with a 12-byte, big-endian header:

```text
 0       4       8  9  10      12
 |conn_id|pay_len|ty|fl|reserved|...payload...
```

- `conn_id`: logical connection identifier
- `pay_len`: payload length; zero-length control frames are valid
- `ty`: message type
- `fl`: flags
- `reserved`: reserved and expected to be zero

### Tunnel configuration constraints

The relay validates its configuration before creating a TUN device or changing host networking:

- In ADB/TCP mode, `--listen` must be an explicit loopback IP literal (normally `127.0.0.1:8765` or `[::1]:8765`). Wildcard/LAN addresses, hostnames, and port `0` are rejected. AOA does not create a TCP listener.
- `--tun-prefix` must be `/24` through `/30`.
- `--mtu` must be `1280` through `1400`.
- `--tun-addr` and `--android-ip` must be distinct usable IPv4 addresses in the same tunnel subnet.
- The stock Android client uses `127.0.0.1:8765`, `10.0.0.1/24`, and MTU `1400`. Changing those values requires a matching client configuration/build; a custom listener port also requires a matching reverse target.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Linux `adb devices` is empty under WSL | Check whether Windows `adb.exe` sees the phone on a different ADB server. Configure `--adb-bin` and `--adb-port` for that server. |
| Device is `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `adb reverse --list` lacks `tcp:8765` | Inspect the same ADB executable/server that owns the device and check the relay watcher logs. If using `--no-adb-watch`, create the mapping manually in the authorized ADB context. |
| `TUNSETIFF ioctl: operation not permitted` | Run the relay with the privileges required for TUN setup. |
| `iptables` or `ip6tables` is missing | Install the host networking dependencies, including `iptables` and `iproute2`. |
| Relay is ready but no Android session connects | Check that the app is running, VPN permission was granted, and the reverse mapping targets the relay listener. |
| VPN stops after screen lock | Check Android battery optimization and OEM background restrictions. |
| VPN starts but traffic does not flow | Test an IPv4 address before a hostname to help separate routing/NAT problems from DNS failures. |
| IPv4 works but IPv6 fails | Validate IPv6 forwarding, host/upstream support, `ip6tables`, and the TUN address separately. |
| AOA fails or starts the wrong transport | Use ADB for baseline beta testing and gather logs before reporting an AOA issue. |

For bug reports, include the Android version/API level, device model, transport mode, relay output, and relevant filtered `adb logcat` output. Sanitize device serials, IDs, public IPs, and other sensitive identifiers before sharing logs.

---

## Project roadmap

See [`ROADMAP.md`](ROADMAP.md) for the detailed plan. The current direction is network reliability (`v0.9.6`), broader Linux compatibility (`v0.9.7`), diagnostics/security (`v0.9.8`), followed by the `v1.0.0` beta and stable release. Roadmap items are planned work, not claims of completed validation.

---

## Contributing

Issues and pull requests are welcome. Keep changes focused and easy to review. Separate major behavioral changes where practical, and distinguish unit-test evidence from physical-device results.

---

License: Apache 2.0
