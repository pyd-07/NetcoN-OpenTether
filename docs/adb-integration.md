# ADB-first integration validation

This guide is the manual, staged procedure for validating the primary ADB
transport on a Linux host with an authorized Android device. It is a procedure,
not a claim of universal compatibility.

The ADB baseline was validated on 2026-10-21 with a POCO C65 (`2310FPCA4I`),
Android 15/API 35, using a WSL2 Debian host and Windows ADB 37.0.1 through
server port 5038. The relay, ADB reverse mapping, VPN/TUN, host NAT, IPv4, DNS,
HTTPS, an approximately 10 MB transfer, app restart, relay restart, USB
reconnect, post-recovery traffic, and clean shutdown/cleanup passed. During
relay downtime the app showed **Waiting for your computer** and reconnected
automatically after relay restart and USB reconnect.

The Android-local IPv6 TUN path (`fdcc::1` ↔ `fdcc::2`) passed, but external
IPv6 was blocked because the host had no global IPv6 address or default route.
Do not advertise external IPv6 Internet support from this run. The documented
`adb install -r` path also failed on the tested MIUI device with
`INSTALL_FAILED_USER_RESTRICTED`; installation succeeded through `adb push`
followed by `pm install -r`. SIGKILL/crash recovery, USB debugging
reauthorization, AOA, other OEMs, other Android versions, and other ADB
topologies were not tested. These results apply only to the tested device and
WSL/Windows-ADB topology.

## 1. Safety and authorization

Run this procedure only on a host and device you are authorized to reconfigure.
The relay runs as root and creates a TUN interface, routes, and NAT rules. A
mistake can disrupt the host's networking. Do not run the procedure in CI, in a
shared environment, or against a device that is not disposable for the test.

- Do not run `sudo ./setup.sh` as part of autonomous validation. The setup helper
  installs packages and changes host configuration; use the explicit commands
  below after reviewing them.
- Do not expose the relay listener beyond loopback. The stock listener is
  `127.0.0.1:8765`, and the relay rejects wildcard/LAN listeners in ADB mode.
- The ADB path is unauthenticated at the application layer. Any local process
  that can reach the loopback listener can inject OTP frames.
- AOA is experimental in this release. Use ADB for baseline validation. AOA
  builds require libusb development metadata and are not covered here.

## 2. Validation layers

Keep these claims separate:

| Layer | What it can prove | What it cannot prove |
|---|---|---|
| Unit tests | Codec/state/config/writer behavior | A device, TUN, routes, or Internet |
| Mock integration tests | Session framing, control replies, cancellation, and the Android↔TUN seam using pipes/test doubles | Real VpnService, ADB reverse, kernel networking |
| Real-device validation | The complete ADB, VPN, TUN, NAT, DNS, and traffic path | Other devices, OEMs, or Android versions |

A passing Go or Kotlin test is not evidence of end-to-end connectivity.

## 3. Stage 0 — environment readiness

Record the host and tool versions before changing anything:

```bash
uname -a
go version
java -version
adb version
command -v adb iptables ip6tables ip
test -e /dev/net/tun && echo TUN-node-present
id -u
```

Expected: Go and Java are available, ADB is installed, the Linux kernel exposes
`/dev/net/tun`, and `iptables`, `ip6tables`, and `ip` are on `PATH`. The relay
uses `ip6tables` during network setup, so an IPv4-only host can still fail at
startup if `ip6tables` is missing.

Record the Android version and API level, device model, and USB/WSL bridge
method. Do not record a real device serial in the final evidence bundle.

For WSL, the Linux `adb` server may not see a Windows-connected phone. Select
one ADB executable and server port and use them for every command below. For
example, use `/mnt/c/.../platform-tools/adb.exe -P 5038` in place of `adb`, or
pass the same values to `setup.sh` with `--adb-bin` and `--adb-port`.

## 4. Stage 1 — build and automated checks

These checks are non-privileged and safe to automate:

```bash
go test -race -count=1 -timeout=120s ./...
go vet ./...
make fmt-check
make test-integration
make test-setup
make shellcheck
bash -n setup.sh
( cd android-client && ./gradlew testDebugUnitTest --no-daemon )
( cd android-client && ./gradlew lint --no-daemon )
( cd android-client && ./gradlew assembleDebug --no-daemon )
make build-go
```

Expected artifact: `./relay-bin` from `make build-go`. The Android debug APK, if
built with `make build-android`, is under
`android-client/app/build/outputs/apk/debug/`.

`make test-integration` is a focused, deterministic relay test target covering
session/framing, writer lifecycle, ADB device/reverse ownership, host-network
setup rollback, and config validation. It does not open `/dev/net/tun`, invoke a
real `adb`, or change host networking: the ADB and networking layers are tested
through injectable command runners and sysctl seams. The full `go test` command
remains the authoritative Go check because it includes the same tests plus the
rest of the package.

AOA compilation is not part of this procedure. If `pkg-config --exists
libusb-1.0` fails, do not install packages just to make an AOA build pass.

## 5. Stage 2 — ADB transport readiness

1. Enable USB debugging on the Android device.
2. Connect USB and authorize the host fingerprint on the device.
3. Inspect authorization without changing USB/IP settings:

```bash
adb devices
```

Expected: the device line ends in `device`, not `unauthorized` or `offline`.
A missing line means the host does not see the device; `unauthorized` means the
device prompt was not accepted; `offline` means the transport is not usable.

4. Install the APK with the same ADB executable and server:

```bash
adb install -r /path/to/OpenTether-<tag>-android.apk
```

On the tested MIUI device, this returned
`Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]`. That is an
OEM/Android policy restriction, not an ADB transport failure. Keep the standard
install as the first attempt; only for that exact failure, use a controlled
fallback with a unique temporary path:

```bash
adb push /path/to/OpenTether-<tag>-android.apk \
  /data/local/tmp/opentether-install-<unique>.apk
adb shell pm install -r /data/local/tmp/opentether-install-<unique>.apk
adb shell rm -f /data/local/tmp/opentether-install-<unique>.apk
```

The helper `scripts/adb-install.sh` implements this fallback for `setup.sh` and
was covered by device-free mock tests. The push + `pm install` sequence itself
succeeded on the tested device. The fallback does not bypass Android security,
use root, or approve a prompt. If the device rejects it too, install the APK
manually on the phone or review the OEM's authorized developer-install setting;
do not treat the restriction as something the relay can always bypass.

5. Prepare reverse forwarding. The relay's ADB watcher normally runs:

```bash
adb -s <serial> reverse tcp:8765 tcp:8765
```

If the relay runs as root and its `adb` cannot see the device (root and user ADB
servers/keys can differ), start the relay with `--no-adb-watch` and run the
reverse command in the same user context that authorized the device:

```bash
adb reverse tcp:8765 tcp:8765
adb reverse --list
```

If no ADB executable on the relay's `PATH` can see the device — for example a
WSL relay where only a Windows ADB server on a non-default port sees the phone
— point the watcher at that executable and server instead:

```bash
sudo ./relay-bin --adb-bin "/mnt/c/Users/<user>/…/platform-tools/adb.exe" --adb-port 5038
```

`--adb-port 0` (the default) keeps the ADB client's own default server port.

Expected: `adb reverse --list` contains `tcp:8765 tcp:8765`. A reverse mapping
only proves the forwarding rule exists; it does not prove the relay is listening
or that the VPN is connected.

Do not use this stage to change USB/IP configuration or to attach a device to a
different USB stack.

## 6. Stage 3 — relay startup and session establishment

Start the relay with the standard build:

```bash
sudo ./relay-bin
```

Use `sudo ./relay-bin --no-adb-watch` only when you configured `adb reverse`
manually in the correct ADB context.

Expected relay observations, in approximate order:

```text
TUN device created: /dev/net/tun → ot0
outbound interface: <interface>
ADB watcher started — automatic device detection and reconnect enabled
ready — waiting for Android on 127.0.0.1:8765
adb watcher: device detected [<redacted-serial>]
adb watcher: reverse tunnel ready for [<redacted-serial>] on port 8765
Android connected from 127.0.0.1:<port> — session starting
```

A `ready — waiting` line without a subsequent `Android connected` line means the
listener is up but the device has not reached it. Check `adb reverse --list`,
the app's VPN state, and whether the relay and `adb` are in the same
authorization context.

The watcher only owns a mapping it observed absent and then created. If the
mapping already exists, the log says `reverse tunnel already present … (not
owned by relay)` and shutdown leaves it in place. If the watcher created the
mapping, shutdown removes it. Session handling is last-connection-wins: a new
Android TCP connection closes the previous session instead of waiting for a
wedged one to end. This is intentional; only one Android session is supported.

The listener is loopback-only. Do not change `--listen` to a LAN/wildcard
address for this test.

## 7. Stage 4 — Android VPN lifecycle

Open the app and tap **Connect** on Home. Grant the VPN consent dialog if prompted. Use **Docs** for the in-app troubleshooting checklist and **Connection settings** for the transport, accessory auto-start, and DNS override.

Useful log filters:

```bash
adb logcat -c
adb logcat -v threadtime OT/VpnService:V OT/TunnelSession:V \
  OT/UsbTunnelClient:V OT/TunReader:V OT/TunWriter:V '*:S'
```

Expected app observations:

```text
OT/VpnService: START received
OT/VpnService: VPN running — connecting via ADB
OT/TunnelSession: session started (ADB)
OT/UsbTunnelClient: started — will connect to 127.0.0.1:8765
OT/UsbTunnelClient: connected to relay
```

The last line, followed by relay `Android connected`, establishes the relay
session. PING/PONG log lines (when verbose logging is enabled) prove the OTP
transport is alive. They do not prove Internet connectivity.

Once the VPN is started manually and traffic is flowing, data frames are
expected. Observed data frames do not by themselves indicate an unexplained
startup or an automatic product defect.

Distinguish these states in the UI and logs:

- VPN permission denied: idle/permission-denied state, no foreground session.
- Foreground/service startup failed: error state and Start available again after
  cleanup (the local sprint added a regression test for this state transition).
- TUN `establish()` failure: service stops; inspect the service error.
- Relay connection failure: the client retries; the relay may show no session.
- Relay connected but no traffic: continue to the connectivity stage; this is
  not automatically a transport failure.

## 8. Stage 5 — IPv4, DNS, HTTP/HTTPS, and large transfer

Run these only after the relay session is established and with authorization to
send real traffic through the tunnel.

```bash
# IPv4 literal: isolates the tunnel/route/NAT path from DNS.
adb shell ping -c 3 1.1.1.1

# Hostname: exercises Android DNS plus the same path.
adb shell ping -c 3 example.com
```

Then open `https://example.com` in the device browser. For a larger transfer,
download a moderately large file through the browser and watch the app/relay
statistics and logs.

Interpretation:

- IPv4 literal fails and no relay session exists: transport/session problem.
- IPv4 literal fails while the session exists: TUN, route, forwarding, NAT, or
  host egress problem.
- IPv4 literal works but hostname fails: DNS problem, not necessarily a tunnel
  failure.
- Hostname resolves but HTTPS fails: upstream server, TLS, captive portal,
  timeout, or MTU problem.
- Small requests work but large transfers stall: suspect MTU/fragmentation or
  upstream path issues; record packet sizes and the exact destination.
- Only some destinations fail: destination-specific routing/firewall or remote
  service problem; do not label the whole tunnel failed without evidence.

Validated result (2026-10-21, single device/host): IPv4 literal and DNS both
returned 3/3 replies with 0% loss, the HTTPS page loaded, and an approximately
10 MB download completed. Host `ot0` counters recorded no errors or drops during
those checks, and NAT counters rose while the tests ran. This is one
device/host result, not a guarantee for other networks or OEMs.

## 9. Stage 6 — IPv6 (separate test)

Treat IPv6 as a separate result. The client routes IPv6 and the relay configures
IPv6 NAT, but compatibility depends on the host and upstream network.

```bash
ip -6 addr show ot0
ip -6 route show
ip6tables -t nat -S
adb shell ping6 -c 3 fdcc::2              # Android-local TUN path
adb shell ping6 -c 3 2606:4700:4700::1111 # external IPv6
```

On the tested device, the Android-local path (`fdcc::1` ↔ `fdcc::2`) passed
with 3/3 replies and 0% loss. External IPv6 was blocked: the host had only a
link-local IPv6 address and no IPv6 default route, so the phone received
`Destination unreachable: No route`. External IPv6 DNS/HTTPS was not run.

Do not mark IPv4 success as IPv6 success, and do not advertise external IPv6
Internet support from this test. A failure here should be reported as a separate
IPv6 finding with host/upstream details.

## 10. Stage 7 — failure and recovery

Run these one at a time and record the observed state:

1. **Relay termination:** stop the relay with Ctrl+C. Expect relay cleanup logs,
   Android disconnect/retry logs, and a recoverable UI state. Restart the relay
   and verify a new `Android connected` session.
2. **ADB disconnection:** unplug the USB cable or revoke debugging authorization.
   Expect `adb devices` to lose the device and the app to log retries. Reconnect,
   re-authorize if needed, and verify `adb reverse --list` and the relay session.
3. **App/service stop:** tap Stop or stop the service from the notification.
   Expect relay `Android session ended` and app `session stopped` / `stopped`.
4. **Partial connection failure:** stop the relay during a transfer, then restart
   it. Check for stale routes, stale reverse mappings, or a UI stuck in
   `Starting`/`Connecting`.

Validated on 2026-10-21 (single tested device and WSL/Windows-ADB topology):

- **Relay termination and restart:** clean SIGTERM shutdown produced
  `shutdown complete`; the UI showed **Waiting for your computer**, not a stale
  `Connected` state. The app retried at 2s→4s→8s→16s→30s and reconnected
  automatically after the relay restarted. IPv4 and DNS passed again afterward.
- **USB unplug/reconnect:** ADB lost the device, the reverse mapping disappeared,
  and the USB reconnect recreated the mapping and reconnected the app without
  manual action. Post-recovery IPv4, DNS, and HTTPS passed.
- **App Stop/Disconnect:** the app stopped its session and service, `tun0`
  disappeared, and the relay reported the session ended.
- **Partial connection failure during a transfer:** not run.
- **SIGKILL/crash recovery:** not run. Validation used clean SIGTERM/Ctrl+C.
  In-process cleanup cannot run after SIGKILL, so do not claim crash cleanup.
- **USB debugging reauthorization:** not run; no authorization prompt appeared
  during the USB reconnect.

Implementation behaviour confirmed by this run or covered by automated/static
review:

- A new Android TCP connection preempts the previous session.
- There is no application-level read/idle deadline. The client sends PINGs every
  2s, but Doze/network suspension can pause them; adding a hard deadline without
  protocol changes would risk disconnecting healthy sessions. A half-open
  session is therefore cleared by the client's reconnect (last-connection-wins)
  rather than by a relay-side timer.
- `FlushWriter.Close` performs a best-effort final flush; a failed flush is
  logged at debug level because the stream is usually already gone.
- Host network setup rolls back resources it created on partial failure. A
  failed rollback is reported as `network cleanup incomplete` and by
  `CleanupErr`; it is not silently discarded.
- The ADB watcher waits for in-flight reverse setup and removes only mappings it
  created, with a bounded shutdown timeout.
- Android reconnect uses 2s→4s→8s→16s→30s backoff, and a reconnect resets the
  session start time instead of inheriting the previous session's clock.

## 11. Stage 8 — cleanup audit

Normal cleanup:

1. Stop the VPN from the app.
2. Stop the relay with Ctrl+C and wait for `shutdown complete`.
3. Inspect (read-only) for leftovers:

```bash
ip link show ot0
ip route show
iptables -S
iptables -t nat -S
ip6tables -S
ip6tables -t nat -S
adb reverse --list
```

Expected: the relay reports removal of its rules/routes, `ot0` is gone, and the
relay's rules are no longer present. A reverse mapping created by the watcher is
removed by the watcher during shutdown. A mapping that pre-existed is not owned
by the watcher and is left in place; `setup.sh` removes a mapping only when that
run created it (and re-checks `adb reverse --list` first), never adopting a
pre-existing mapping. If an entry remains, remove it manually only after
confirming it is the test mapping and after authorization:

```bash
adb reverse --remove tcp:8765
```

Do not use an automatic cleanup script on an unknown host. If leftovers exist,
record the exact output and stop; manual remediation requires separate review.

Validated cleanup result (2026-10-21): after the final app stop and clean relay
SIGTERM, the relay reported `shutdown complete`; its owned reverse mapping,
rules, and routes were removed; `ot0` was gone; the forwarding sysctls were
restored; and no process was listening on port 8765. This does not establish
cleanup after SIGKILL or after a failed rollback.

## 12. Evidence bundle

Create a bundle from the authorized run. Sanitize before sharing:

```text
evidence/
  meta.txt          # date, branch, HEAD commit, host OS/kernel, Android version/API
  versions.txt      # go, java, adb, gradle, relay version
  checks.txt        # commands and PASS/FAIL/BLOCKED results
  relay.log         # relay stdout/stderr
  app-logcat.txt    # filtered app logs
  adb-state.txt     # sanitized adb devices/reverse output
  net-state.txt     # sanitized host link/route/firewall observations
  outcome.md        # stage-by-stage result and unresolved failures
  artifacts.sha256  # checksums of relay/APK used
```

Redact device serials, Android ID, IMEI/MEID, MAC addresses, public IP addresses,
SSIDs, hostnames, USB bus/port identifiers, account names, tokens, and private
keys. Replace them with stable placeholders such as `<device-serial>`. Do not
include raw `adb devices -l` or full `adb logcat` output without review.

## 13. What is safe to automate

| Check | Safe to automate? | Reason |
|---|---|---|
| `go test`, `go vet`, `make fmt-check` | Yes | Local, non-privileged |
| `make test-integration` | Yes | Pipe/test-double session tests; no TUN or device |
| `make test-setup` | Yes | Mock ADB install/fallback tests; no device or host network change |
| Gradle unit tests / debug build | Yes | No device or host network change |
| `adb version`, `adb devices`, `adb reverse --list` | Read-only inspection, yes | Starts a local adb server; does not change USB/IP or host networking |
| `adb install`, `adb reverse`, `adb reverse --remove` | Manual/authorized | Changes device state |
| Relay startup, TUN, routes, NAT/firewall, DNS, IPv6 | Manual/privileged | Changes host networking |
| Real traffic, USB replug, device authorization | Manual/authorized | Physical hardware and disruptive state |

## 14. Known limitations and validation status

- **Validated baseline (single device/topology):** POCO C65 (`2310FPCA4I`),
  Android 15/API 35, WSL2 Debian host, Windows ADB 37.0.1 on server port 5038.
  Relay startup, ADB reverse mapping, VPN/TUN, host NAT, IPv4, DNS, HTTPS, an
  approximately 10 MB transfer, app restart, relay restart, USB reconnect,
  post-recovery traffic, and clean shutdown/cleanup passed. These results do
  not establish compatibility with other devices, OEMs, Android versions, ADB
  topologies, or Linux hosts.
- **IPv6 is partial:** the Android-local TUN path passed, but external IPv6 was
  blocked because the host had no global IPv6 address or default route. External
  IPv6 DNS/HTTPS was not run. Do not claim IPv6 Internet support from this run.
- **MIUI install restriction:** the standard `adb install -r` path failed with
  `INSTALL_FAILED_USER_RESTRICTED`; the tested push + `pm install -r` sequence
  succeeded. The setup helper implements that controlled fallback only for the
  exact restriction, with a unique temporary APK and cleanup. If the fallback
  is rejected too, the user must install manually or change the authorized
  developer-install setting; Android security is not bypassed.
- **Clean shutdown only:** clean SIGTERM/Ctrl+C shutdown is the supported tested
  path. A SIGKILL/crash has no opportunity to run the in-process undo list;
  static inspection indicates stale rules and forwarding sysctls may persist, so
  crash recovery is **not validated** and must not be claimed.
- **Not tested:** USB debugging reauthorization, a partial failure during a large
  transfer, other OEMs, other Android versions, other Linux hosts, other ADB
  server/device topologies, and AOA negotiation or bulk transfers.
- **AOA remains experimental and outside this baseline.** AOA builds require
  libusb metadata. The relay sends accessory version `1`; APKs built against the
  older `1.0` filter will not match. Do not present AOA as validated support.
- The relay listener is loopback-only but unauthenticated; local processes can
  inject frames.
- The relay and Android client have bounded retry/backoff logic. A reconnect
  creates a new session clock on the Android side; the relay still preempts the
  previous Android connection rather than multiplexing sessions.
- The Android side still has static-review risks around foreground-service types,
  notification permission, and retry loops on fd/protect failures; the fd/protect
  busy loop is fixed, but broad device confirmation is not claimed.
- Device-free tests and mock integration tests prove only their tested surfaces.
  They do not open a real TUN device, change host networking, or prove
  end-to-end connectivity.

For the current build/test commands and troubleshooting table, see
[`../README.md`](../README.md).
