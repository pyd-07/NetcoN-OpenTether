# OpenTether Release Roadmap

The goal of the next releases is to make OpenTether reliable across a broad range of Android devices and Linux hosts before declaring a stable 1.0 release.

The roadmap is intentionally split into small releases so that each phase can be tested and released independently.

## v0.9.4 - Stability Release

**Goal:** Make the existing ADB and AOA implementations reliable before expanding compatibility.

### Improved

- Improved ADB connection detection.
- Improved AOA accessory detection.
- Improved relay session lifecycle.
- Improved shutdown and resource cleanup.
- Improved error reporting for failed transport connections.
- Added bounded reconnect backoff.
- Added explicit connection states.

### Scope

- Fix ADB reconnect after USB disconnect/reconnect.
- Fix relay connection state after unexpected disconnects.
- Clean up stale VPN/TUN state after failed sessions.
- Ensure NAT/firewall cleanup on relay termination.
- Fix AOA cleanup after USB disconnect.
- Prevent multiple sessions during reconnect.
- Keep VPN state synchronized with relay state.
- Reset RTT and statistics after disconnect.
- Test USB unplug/replug, relay restart, VPN stop/start, and screen lock/unlock.

**Exit condition:** A normal USB disconnect/reconnect should restore internet access without restarting the application.

## v0.9.5 - Android Compatibility

**Goal:** Handle Android versions, lifecycle behavior, and OEM restrictions correctly.

### Phase 1 — Fixed

- Fix VPN service behavior after screen lock.
- Fix VPN service behavior after process recreation.
- Fix foreground-service startup failures.
- Fix VPN shutdown after system service recreation.
- Fix USB permission handling across Android versions.
- Fix AOA permission handling on newer Android releases.
- Fix Activity/service lifecycle race conditions.

### Phase 2 — Added

- Add device information detection.
- Add battery optimization diagnostics.
- Add explicit Android compatibility diagnostics.
- Add lifecycle and USB permission test coverage where practical.

### Phase 3 — Improved

- Improve VPN service lifecycle handling.
- Improve foreground-service behavior on Android 14+.
- Improve service recreation and process-death recovery.
- Improve screen lock/unlock behavior.
- Improve USB permission handling across Android versions.
- Improve AOA permission handling.
- Test Android 8 through the latest supported Android release.
- Test Pixel, Samsung, Xiaomi, OnePlus, Motorola, Oppo, Realme, and Vivo devices.

**Exit condition:** The VPN survives screen lock/unlock and common Android service recreation without requiring an application restart.

## v0.9.6 - Network Reliability

**Goal:** Make the actual internet connection reliable across common traffic types and network configurations.

- Fix DNS failures and DNS recovery after reconnect.
- Validate IPv4 routing.
- Validate IPv6 routing and prevent IPv6 blackholes.
- Improve UDP and QUIC reliability.
- Test large packets and MTU behavior.
- Detect Linux host network-interface changes.
- Add DNS connectivity checks.
- Add configurable DNS support.
- Add VPN connectivity health checks.
- Expose MTU and IPv4/IPv6 status in diagnostics.
- Test TCP, UDP, DNS, QUIC, HTTPS, streaming, large downloads, and long-lived connections.

**Exit condition:** Common TCP, UDP, DNS, IPv4, and IPv6 workloads work consistently without unexplained failures.

## v0.9.7 - Linux Compatibility

**Goal:** Expand beyond a narrow Debian/Ubuntu setup and make installation predictable.

- Detect Linux distribution and architecture.
- Detect firewall backend.
- Support and test iptables/nftables environments.
- Test UFW and firewalld environments.
- Validate amd64 and arm64 builds.
- Test Ubuntu 22.04, Ubuntu 24.04, Debian 12, Debian 13, Fedora, and Arch Linux.
- Improve dependency detection and installation.
- Improve Go, Java, ADB, and libusb checks.
- Add clearer setup errors.
- Add `--check`, `--dry-run`, and `--uninstall` setup options.
- Improve cleanup of routes, TUN interfaces, and firewall rules.

**Exit condition:** A fresh supported Linux installation can be prepared and connected without manual networking troubleshooting.

## v0.9.8 - Diagnostics and Security

**Goal:** Make failures diagnosable and reduce unnecessary attack surface.

- Add Android diagnostics for device, Android version, transport, USB, VPN, relay, IPv4, IPv6, DNS, MTU, RTT, traffic, reconnect count, and last error.
- Add diagnostic and log export.
- Add structured connection events.
- Improve error messages and logging levels.
- Audit relay listening addresses and unnecessary LAN exposure.
- Review ADB and AOA permission assumptions.
- Check IPv4, IPv6, and DNS leakage.
- Audit privileged Linux operations and shell scripts.
- Harden handling of invalid protocol and configuration input.

**Exit condition:** A device-specific failure can be diagnosed from exported diagnostics instead of requiring source-level debugging.

## v1.0.0-beta.1 - Public Compatibility Release

**Goal:** Turn the project into a release that normal users can install and test without building from source.

- Publish Android APK releases.
- Publish Linux amd64 and arm64 relay binaries.
- Publish AOA relay binaries.
- Publish SHA256 checksums.
- Automate Android, relay, AOA, test, checksum, and GitHub release builds.
- Make version numbers consistent across Android, relay, setup script, README, tags, and releases.
- Document installation, upgrades, uninstallation, and troubleshooting.
- Publish the device and Linux compatibility matrix.
- Document known limitations.

**Exit condition:** A new user can download the correct release artifacts, follow the documentation, and establish a connection without reading the source code.

## v1.0.0 - Stable

**Goal:** Release a compatibility-tested, maintainable stable version.

- Stabilize ADB transport.
- Stabilize AOA transport.
- Stabilize IPv4, IPv6, DNS, TCP, and UDP.
- Verify reconnect behavior.
- Verify screen lock/unlock behavior.
- Verify USB reconnect behavior.
- Verify relay restart recovery.
- Verify Android service recreation.
- Maintain Android and Linux compatibility matrices.
- Maintain automated tests and release builds.
- Publish checksums and documented release artifacts.
- Document known limitations and supported configurations.

The stable release should be based on real device and Linux-host testing rather than an assumption of universal compatibility.
