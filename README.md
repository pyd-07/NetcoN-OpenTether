# NetcoN OpenTether

[![Release](https://img.shields.io/github/v/release/pyd-07/NetcoN-OpenTether?include_prereleases)](https://github.com/pyd-07/NetcoN-OpenTether/releases)
[![CI](https://github.com/pyd-07/NetcoN-OpenTether/actions/workflows/ci.yml/badge.svg)](https://github.com/pyd-07/NetcoN-OpenTether/actions/workflows/ci.yml)

Reverse USB tethering for Android: route your Android device's traffic through a Linux PC's Internet connection over USB, without root on the phone.

> **Release status:** OpenTether is in the `v0.9.5` beta series. Use [GitHub Releases](https://github.com/pyd-07/NetcoN-OpenTether/releases) for the latest published prerelease and its matching assets. The ADB baseline passed real-device validation on one POCO C65 running Android 15, but compatibility with other devices and hosts is not established. ADB is the recommended test path; AOA remains experimental and was not validated in this release.

---

## For users

### Requirements

- Android 8.0+ (API 26+); only Android 15/API 35 has been device-validated so far
- A USB data cable
- A Linux PC with Internet access and root/`sudo` for the relay's TUN and NAT setup
- ADB for the recommended transport, or USB/libusb access for experimental AOA
- `iptables`, `ip6tables`, and `iproute2` on the Linux PC
- A kernel that exposes `/dev/net/tun`
- On WSL2, a Windows ADB executable/server that can see the phone (see below)

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

4. Install the APK downloaded from that same release, using the same ADB
   executable/server that sees the phone:

   ```bash
   adb install -r /path/to/OpenTether-<tag>-android.apk
   ```

   On the tested MIUI device this failed with
   `INSTALL_FAILED_USER_RESTRICTED`. The setup helper retries only that exact
   restriction with a unique temporary device-side APK and `pm install -r`:

   ```text
   adb push <apk> /data/local/tmp/opentether-<unique>.apk
   adb shell pm install -r /data/local/tmp/opentether-<unique>.apk
   adb shell rm -f /data/local/tmp/opentether-<unique>.apk
   ```

   The fallback does not bypass Android security, use root on the phone, or
   approve prompts. If the device rejects it too, install the APK manually or
   review the OEM's authorized developer-install setting. Unrelated install
   failures are reported directly and do not trigger the fallback.

5. Start the matching Linux relay as root:

   ```bash
   sudo ./OpenTether-<tag>-linux-amd64
   ```

6. Open OpenTether on Android, grant VPN permission if prompted, and tap **Connect** on Home.
7. Verify actual connectivity through the Linux host. A successful build, a visible ADB device, or a reverse mapping alone does not prove that Internet traffic works.

### Stop and clean up

1. Tap **Disconnect** in OpenTether and wait for the VPN/service to stop.
2. In the relay terminal, press **Ctrl+C** once and wait for `shutdown complete`.
   Do not use `SIGKILL`/`kill -9`; in-process cleanup cannot run after SIGKILL.
3. Confirm the host was restored:

   ```bash
   ip link show ot0
   ip route show
   iptables -S
   iptables -t nat -S
   ip6tables -S
   ip6tables -t nat -S
   adb reverse --list
   ss -lntp | grep 8765 || true
   ```

   Expected: `ot0` is gone, the relay's owned rules/routes are removed,
   `adb reverse --list` no longer shows the mapping created by this run, and
   nothing is listening on port 8765. The relay listener must stay on
   `127.0.0.1:8765`; never expose it on the LAN.

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

Example for the relay:

```bash
sudo ./relay-bin \
  --adb-bin "/mnt/c/Users/<user>/.../platform-tools/adb.exe" \
  --adb-port 5038
```

Example for the setup script:

```bash
sudo ./setup.sh \
  --adb-bin "/mnt/c/Users/<user>/.../platform-tools/adb.exe" \
  --adb-port 5038
```

The setup script uses the selected executable and port for `adb devices`,
installation, reverse setup, cleanup, and the relay invocation. Do not mix a
Windows ADB server on port 5038 with the Linux ADB server on the default port
5037 for different steps.

- `--adb-bin` selects the ADB executable used by automatic reverse setup. The default is `adb` from `PATH`.
- `--adb-port` selects the ADB server port. The default `0` preserves the ADB client's default-port behavior; set a nonzero value such as `5038` when that is the server that sees your phone.

Use a real executable path for your Windows installation. Do not copy the illustrative path literally. Check device visibility first with the same executable and port:

```bash
"/path/to/adb.exe" -P 5038 devices
"/path/to/adb.exe" -P 5038 reverse --list
```

An empty device list on one ADB server does not mean the phone is disconnected from every ADB server. Always inspect the server that owns the device transport.

### AOA mode — experimental

AOA (Android Open Accessory) is intended to provide direct USB transport without persistent USB debugging. It is **not part of the v0.9.5 validated baseline**: accessory negotiation, permission prompts, and real USB bulk transfers were not tested. Use ADB for baseline testing and do not treat the AOA assets as validated support.

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

To build and install without starting the privileged relay:

```bash
sudo ./setup.sh --build-only
```

On WSL, pass the Windows ADB executable and server port selected above:

```bash
sudo ./setup.sh --adb-bin "/mnt/c/Users/<user>/.../platform-tools/adb.exe" --adb-port 5038
```

For the AOA setup path (experimental, not validated):

```bash
sudo ./setup.sh --aoa
```

The setup script installs host dependencies and builds/configures a local installation. It may change host configuration; review it and its cleanup behavior before running it. Prebuilt release assets are usually simpler for beta testing.

> **Networking caution:** the relay runs with elevated privileges, creates a TUN interface, and configures routes and NAT/firewall rules. Use it only on a host you are authorized to reconfigure, keep the listener loopback-only, and follow the documented cleanup audit after testing.

---

## Current beta status

The `v0.9.5` beta series combines relay hardening, Android lifecycle/session-startup improvements, and a connection-first UI.

### Recent changes

- **ADB baseline hardening:** track reverse-mapping ownership per device and remove only mappings the watcher created, bound every watcher-owned `adb` command with a timeout, wait for in-flight setup at shutdown, and make session handling last-connection-wins so a wedged session cannot block reconnect.
- **Networking and transport safety:** roll back partially created host networking resources, restore only sysctls this run changed, scope firewall rules, report incomplete cleanup, use an exclusive TUN interface, flush buffered frames on writer close, and coalesce PONG replies.
- **Android reconnect/startup recovery:** replace fd/protect busy loops with bounded 2s→30s backoff, shut sockets down before closing, bound session-cleanup joins, reset the session clock on reconnect, and surface foreground-service start/stop failures instead of leaving the UI stuck.
- **Connection-first Android UI:** reduce the main navigation to Home and Docs, show explicit connection states and the relevant primary action, add connection settings and troubleshooting guidance, and remove obsolete screens/components.
- **Configurable ADB for WSL:** add `--adb-bin` and `--adb-port` so automatic reverse mapping can target a non-default ADB server, with diagnostics for repeated empty device discovery and focused watcher/configuration tests.

These are implementation changes, not a claim that every device, host, or transport combination has passed physical validation.

### Validation status

The repository includes automated Go tests, race-enabled tests, vet/lint checks, deterministic relay session tests, Android unit tests, and release CI. The ADB integration guide includes the current staged commands and the limits of each validation layer.

Automated and mock integration tests do **not** open a real TUN device, configure host routing/NAT, perform USB accessory negotiation, or prove end-to-end Internet connectivity. Passing them does not establish that the VPN works on every Android device.

### Device validation for v0.9.5

On 2026-10-21, the ADB baseline passed on a POCO C65 (`2310FPCA4I`) running
Android 15/API 35, with a WSL2 Debian host and Windows ADB 37.0.1 on server port
5038. The tested path covered relay startup, ADB reverse mapping, VPN/TUN, host
NAT, IPv4, DNS, HTTPS, an approximately 10 MB transfer, app restart, relay
restart, USB reconnect, post-recovery traffic, and clean shutdown/cleanup.
During relay downtime the UI showed **Waiting for your computer**; the app
retried at 2s→4s→8s→16s→30s and reconnected automatically after the relay
restarted and after USB reconnect.

The Android-local IPv6 TUN path (`fdcc::1` ↔ `fdcc::2`) passed. External IPv6
was blocked because the host had no global IPv6 address or default route, so
this release does not claim external IPv6 support. The documented
`adb install -r` path failed on this MIUI build with
`INSTALL_FAILED_USER_RESTRICTED`; push + `pm install -r` succeeded, and the
setup helper now implements that narrowly gated fallback.

Not validated in this release:

- SIGKILL/crash recovery; only clean SIGTERM/Ctrl+C shutdown is supported
- USB debugging reauthorization
- Relay failure during a deliberate large transfer
- AOA accessory negotiation, permission prompts, or bulk transfers
- Other OEMs, Android versions, Linux hosts, and ADB device/server topologies
- External IPv6 DNS/HTTPS

These results apply only to the tested device and WSL/Windows-ADB topology.
ADB remains the recommended baseline. See
[`docs/adb-integration.md`](docs/adb-integration.md) for the staged procedure,
expected observations, limitations, and evidence requirements.

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
make vet
make test
make test-go
make test-integration
make test-setup
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
go build -o relay-bin ./cmd/opentether
```

AOA-enabled relay (requires libusb development files):

```bash
go build -tags aoa -o relay-bin ./cmd/opentether
```

### Release artifacts

Pushing a version tag matching `v*` triggers the release workflow. For example, a tag such as `v0.9.5` causes the workflow to:

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
