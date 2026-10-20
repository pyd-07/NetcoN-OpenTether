# ADB-first integration validation

This guide is the manual, staged procedure for validating the primary ADB
transport on a Linux host with an authorized Android device. It is a procedure,
not evidence that every stage has been executed. The 2026-10-20 local automated
sprint ran only non-privileged builds and unit tests. A separate manual ADB run
established the reverse transport, the app-owned relay control session,
PING/PONG, the VPN/TUN data path, and cleanup that restored the host networking
baseline; real background traffic was observed after the VPN was started
manually. Deliberate DNS/HTTP(S)/large-transfer, IPv6, reconnect, and full
end-to-end validation remain outstanding.

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

## 4. Stage 1 — build and automated checks

These checks are non-privileged and safe to automate:

```bash
go test -race -count=1 -timeout=120s ./...
go vet ./...
make fmt-check
make test-integration
make shellcheck
bash -n setup.sh
( cd android-client && ./gradlew testDebugUnitTest --no-daemon )
make build-go
```

Expected artifact: `./relay-bin` from `make build-go`. The Android debug APK, if
built with `make build-android`, is under
`android-client/app/build/outputs/apk/debug/`.

`make test-integration` is a focused, deterministic relay test target. It does
not open `/dev/net/tun`, invoke `adb`, or change host networking. The full
`go test` command remains the authoritative Go check because it includes the
same tests plus the rest of the package.

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

4. Install the APK:

```bash
adb install -r /path/to/OpenTether-v0.9.5-beta.1-android.apk
```

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

## 9. Stage 6 — IPv6 (separate test)

Treat IPv6 as a separate result. The client routes IPv6 and the relay configures
IPv6 NAT, but compatibility depends on the host and upstream network.

```bash
ip -6 addr show ot0
ip6tables -t nat -S
adb shell ping -6 -c 3 2606:4700:4700::1111
```

If the Android shell lacks `ping -6`, try `ping6` or a browser IPv6 test. Record
whether IPv6 is expected to work on the host's upstream network. Do not mark
IPv4 success as IPv6 success. A failure here should be reported as a separate
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

Current implementation risks to watch for (static review, not yet confirmed on a
device): the session accepts one connection at a time, has no read/idle deadline,
`FlushWriter.Close` can drop buffered frames, network setup is not fully
transactional on partial failure, and the ADB watcher does not remove its
`adb reverse` mapping during shutdown. Record which of these actually reproduces.

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
relay's rules are no longer present. An `adb reverse` entry may remain; remove it
manually only after confirming it is the test mapping and after authorization:

```bash
adb reverse --remove tcp:8765
```

Do not use an automatic cleanup script on an unknown host. If leftovers exist,
record the exact output and stop; manual remediation requires separate review.

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
| Gradle unit tests / debug build | Yes | No device or host network change |
| `adb version`, `adb devices`, `adb reverse --list` | Read-only inspection, yes | Starts a local adb server; does not change USB/IP or host networking |
| `adb install`, `adb reverse`, `adb reverse --remove` | Manual/authorized | Changes device state |
| Relay startup, TUN, routes, NAT/firewall, DNS, IPv6 | Manual/privileged | Changes host networking |
| Real traffic, USB replug, device authorization | Manual/authorized | Physical hardware and disruptive state |

## 14. Known limitations and validation status

- A manual ADB device run on 2026-10-20 established the reverse transport,
  app-owned relay control session, PING/PONG, the VPN/TUN data path, and cleanup
  restoring the host networking baseline; real background traffic was observed
  after the VPN was started manually. Deliberate DNS/HTTP(S)/large-transfer,
  IPv6, reconnect, and full end-to-end validation remain outstanding.
- AOA is experimental. The relay sends accessory version `1`; APKs built against
  the older `1.0` filter will not match. AOA builds require libusb metadata.
- The relay listener is loopback-only but unauthenticated; local processes can
  inject frames.
- The relay and Android client have retry logic, but bounded reconnect behavior
  still needs device validation. Cleanup restored the host networking baseline
  in the 2026-10-20 manual run.
- The Android side has static-review risks around foreground-service types,
  notification permission, and retry loops on fd/protect failures; these require
  device confirmation and are not claimed fixed here.
- A passing mock test proves only the tested protocol/session seam. Use the
  staged procedure above for real-device evidence.

For the current build/test commands and troubleshooting table, see
[`../README.md`](../README.md).
