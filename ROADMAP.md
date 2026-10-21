# OpenTether Release Roadmap

The goal of the next releases is to make OpenTether reliable across a broad range of Android devices and Linux hosts before declaring a stable 1.0 release.

The roadmap is intentionally split into small releases so that each phase can be tested and released independently. Completed implementation work does not count as an exit criterion until the required device and host validation has also been performed.

## v0.9.4 - Stability Release

**Goal:** Make the existing ADB and AOA implementations reliable before expanding compatibility.

### Implemented

- Improved ADB device-state parsing and bounded reverse-setup backoff.
- Improved relay session lifecycle, startup-error classification, and cancellation paths.
- Added explicit connection states and stronger validation before creating a TUN device or changing host networking.
- Added short-write detection across protocol, TUN, direct USB, and buffered-flush paths.
- Improved error handling and tests around AOA string/filter compatibility.

### Validation still needed

- Verify ADB reconnect after real USB disconnect/reconnect.
- Verify relay connection state after unexpected disconnects.
- Verify stale VPN/TUN state is removed after failed sessions.
- Verify NAT/firewall cleanup on relay termination and partial startup failure.
- Verify AOA cleanup after USB disconnect.
- Verify multiple sessions are not created during reconnect.
- Verify VPN state remains synchronized with relay state.
- Verify screen lock/unlock, VPN stop/start, and relay restart recovery on physical devices.

**Exit condition:** A normal USB disconnect/reconnect should restore Internet access without restarting the application. Automated tests cover parts of session and cleanup behavior, but do not satisfy this real-device exit condition.

## v0.9.5 - Android Compatibility

**Goal:** Handle Android versions, lifecycle behavior, and OEM restrictions correctly.

### Implemented

- Improved foreground-service startup failure handling so startup errors can be distinguished from runtime reconnect failures and the user can retry after cleanup.
- Added lifecycle-aware VPN session handling and tests around startup-error transitions.
- Added Android API-level compatibility handling, device information detection, battery optimization diagnostics, and Android-specific troubleshooting information.
- Reworked the Android UI around **Home** and **Docs**, with explicit connection states and a primary action that reflects the current state.
- Added connection settings for transport selection, accessory auto-start, and DNS override.
- Added documentation and troubleshooting content in the app and repository.
- Expanded Go relay, ADB watcher/configuration, protocol/session, and Android lifecycle test coverage.

### Validated in the v0.9.5 testing cycle

- The ADB baseline passed on one POCO C65 (`2310FPCA4I`), Android 15/API 35,
  using WSL2 Debian and Windows ADB 37.0.1 on server port 5038. Relay startup,
  reverse mapping, VPN/TUN, NAT, IPv4, DNS, HTTPS, an approximately 10 MB
  transfer, relay restart, USB reconnect, post-recovery traffic, and clean
  shutdown/cleanup passed.
- The UI showed **Waiting for your computer** during relay downtime and
  reconnected automatically after relay restart and USB reconnect.
- The Android-local IPv6 TUN path passed; external IPv6 was blocked by the
  host's missing global IPv6 address/default route.
- The MIUI `INSTALL_FAILED_USER_RESTRICTED` install path was handled by the
  tested push + `pm install -r` fallback; the setup helper has device-free tests
  for the narrow retry and cleanup behavior.

### Remaining validation

- Test Android 8 through the latest supported Android release beyond the single
  Android 15 device used here.
- Test a representative range of Pixel, Samsung, Xiaomi, OnePlus, Motorola, Oppo, Realme, and Vivo devices.
- Verify screen lock/unlock, service recreation, process-death recovery, and Android 14+ foreground-service behavior on devices.
- Verify USB debugging reauthorization, SIGKILL/crash recovery, and failure during a large transfer.
- Verify external IPv6 on a host with a working global IPv6 upstream.
- Validate AOA permission and accessory handling on real devices.

**Exit condition:** The VPN survives screen lock/unlock and common Android service recreation without requiring an application restart. The implementation includes lifecycle handling, but broad device coverage remains outstanding.

## v0.9.6 - Network Reliability

**Goal:** Make the actual Internet connection reliable across common traffic types and network configurations.

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
- Improve error messages and logging levels, including actionable ADB discovery diagnostics.
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
- Publish SHA-256 checksums.
- Automate Android, relay, AOA, test, checksum, and GitHub release builds.
- Make version numbers consistent across Android, relay, setup script, README, tags, and releases.
- Document installation, upgrades, uninstallation, and troubleshooting.
- Publish the device and Linux compatibility matrix.
- Document known limitations.

**Status:** Release automation and release assets are present in the repository. A successful release build is not evidence that all advertised transports or network paths have passed physical-device testing.

**Exit condition:** A new user can download the correct release artifacts, follow the documentation, and establish a connection without reading the source code.

## v1.0.0 - Stable

**Goal:** Release a compatibility-tested, maintainable stable version.

- Stabilize ADB transport, including configurable executable/server selection for WSL setups and verified reconnect behavior.
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

The stable release should be based on real-device and Linux-host testing rather than an assumption of universal compatibility.

## Validation principles

Keep the following evidence levels separate:

- **Unit tests:** validate individual functions, parsing, configuration, and state transitions.
- **Mock integration tests:** validate protocol/session behavior with test doubles; they do not exercise kernel TUN, real USB, or Internet traffic.
- **Physical-device validation:** required to establish USB/ADB behavior, Android VPN lifecycle behavior, routes/NAT, DNS, IPv4/IPv6, and real connectivity.

Current status: automated Go and Android test coverage has been expanded, and a manual ADB run has established portions of the transport and VPN/TUN data path. Deliberate DNS/HTTPS/large-transfer, IPv6, reconnect, broad device/OEM, and AOA physical-device validation remain outstanding. See [`docs/adb-integration.md`](docs/adb-integration.md) for the staged procedure and evidence requirements.
