# OpenTether
### This was build by student to solve his no easy way of hotspot problem in Linux(Debian). Build with Claude and GPT.
Reverse USB tethering for Android — route your phone's internet traffic through your PC via USB, without root.

```
Android app → VpnService (TUN) → ADB reverse tunnel → Go relay → internet
```

Similar to [Gnirehtet](https://github.com/Genymobile/gnirehtet) and [Tetrd](https://tetrd.app), but fully open-source, modular, and written to be read and extended.

---

## How it works

Android's `VpnService` API lets an app capture all network traffic without root by creating a virtual TUN interface. OpenTether reads raw IP packets off that interface, wraps them in a lightweight binary protocol (OTP — OpenTether Protocol), and sends them over a TCP socket to a relay server running on the PC.

The relay receives the packets, routes them to the internet via NAT (iptables MASQUERADE), and sends responses back the same way. The ADB reverse tunnel (`adb reverse tcp:8765 tcp:8765`) carries the TCP connection over the USB cable — no Wi-Fi, no Bluetooth, no extra network configuration needed.

```
┌─────────────────────────────┐    USB + ADB     ┌──────────────────────────┐
│        Android              │ ◄──────────────► │          PC              │
│                             │                  │                          │
│  App traffic                │                  │  Go relay (127.0.0.1)   │
│    ↓                        │                  │    ↓                     │
│  VpnService (TUN iface)     │                  │  TUN device (ot0)       │
│    ↓                        │                  │    ↓                     │
│  TunReader coroutine        │                  │  iptables MASQUERADE    │
│    ↓                        │                  │    ↓                     │
│  UsbTunnelClient  ──────────────────────────►  │  Internet               │
│    ↑                        │                  │    ↓                     │
│  TunWriter coroutine ◄──────────────────────── │  relay → Android        │
└─────────────────────────────┘                  └──────────────────────────┘
```

---

## Requirements

**PC (relay server)**
- Linux (kernel 3.17+ for TUN support)
- Go 1.22+
- `ip`, `iptables` on PATH
- Root or `CAP_NET_ADMIN` capability
- ADB installed

**Android (client)**
- Android 8.0+ (API 26)
- USB debugging enabled
- No root required

---

## Working

#### Cloning the repo

```bash
git clone https://github.com/pyd-07/NetcoN-OpenTether.git
cd NetcoN-OpenTether
```

### Quick Setup

```bash
chmod +x ./setup.sh
sudo ./setup.sh
```

### Manual Steps

#### 1. Build and start the relay

```bash
go build -o relay ./...
sudo ./relay/...
```

The relay creates a TUN interface (`ot0`), sets up NAT, and listens on `127.0.0.1:8765`.



#### 2. Connect your phone and set up the ADB tunnel

```bash
adb devices                     # confirm phone is listed as "device"
adb reverse tcp:8765 tcp:8765   # maps phone:8765 → PC:8765 over USB
```

#### 3. Install and start the Android app

```bash
cd android-client
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.opentether/.MainActivity
```

Tap **Start VPN** on the phone. Accept the system VPN consent dialog on first run. Your phone's traffic now routes through the PC.

### Stop

Tap **Stop VPN** on the phone, then on the PC:

```bash
# Ctrl+C in the relay terminal — it removes all iptables rules on exit
adb reverse --remove tcp:8765
```

---

## Relay server flags

```
-listen      TCP address to listen on (default: 127.0.0.1:8765)
-tun         TUN interface name       (default: ot0)
-tun-addr    Relay TUN IP address     (default: 10.0.0.2)
-tun-prefix  TUN prefix length        (default: 24)
-android-ip  Android client VPN IP   (default: 10.0.0.1)
-out-iface   Outbound interface       (default: auto-detect from default route)
-mtu         MTU for TUN and buffers  (default: 1500)
-v           Verbose per-packet logs
```

Example with explicit interface:

```bash
sudo ./relay -out-iface wlan0 -v
```

---

## Project structure

```
opentether/
├── main.go                          Entry point, flag parsing
├── relay/
│   ├── config.go                    Configuration struct
│   ├── protocol.go                  OTP binary frame encode/decode
│   ├── protocol_test.go             Protocol unit tests
│   ├── tun_linux.go                 TUN device via TUNSETIFF ioctl
│   ├── nat_linux.go                 ip/iptables setup and cleanup
│   ├── session.go                   Per-connection bridge goroutines
│   ├── server.go                    TCP listener and session lifecycle
│   └── log.go                       Levelled logging
│
├── android-client/
│   └── app/src/main/kotlin/com/opentether/
│       ├── Constants.kt             All tunable values in one place
│       ├── MainActivity.kt          UI: start/stop, VPN consent flow
│       ├── model/
│       │   └── OtpFrame.kt          Decoded protocol frame data class
│       ├── vpn/
│       │   ├── OpenTetherVpnService.kt   VpnService, TUN setup, lifecycle
│       │   ├── TunReader.kt         FileInputStream → Channel<ByteArray>
│       │   └── TunWriter.kt         Channel<ByteArray> → FileOutputStream
│       └── tunnel/
│           ├── PacketEncoder.kt     Raw IP bytes → OTP frame ByteArray
│           ├── PacketDecoder.kt     DataInputStream → OtpFrame
│           └── UsbTunnelClient.kt   Socket management, send/receive loops
│
└── tools/
    └── mock_android/
        └── main.go                  Test client: sends a real DNS query
                                     through the relay without an Android device
```

---

## OTP protocol

Every message between the Android client and the PC relay is a framed OTP packet. The header is 12 bytes, big-endian.

```
 0       4       8  9  10      12
 |conn_id|pay_len|ty|fl|reservd|...payload...

conn_id     uint32   Logical connection ID (assigned by Android client)
pay_len     uint32   Payload length in bytes (0 is valid)
ty          uint8    Message type
fl          uint8    Flags bitmask
reserved    uint16   Must be zero
payload     bytes    Raw IP packet, or UTF-8 error string for MSG_ERROR
```

**Message types**

| Type | Value | Meaning |
|------|-------|---------|
| MSG_DATA | 0x01 | Raw IP packet — bidirectional |
| MSG_CONNECT | 0x02 | New connection intent |
| MSG_CLOSE | 0x03 | Graceful connection teardown |
| MSG_ERROR | 0x04 | Error report (payload = message string) |
| MSG_PING | 0x05 | Keepalive probe |
| MSG_PONG | 0x06 | Keepalive reply |

A single TCP connection (the ADB tunnel) carries all frames for all logical connections, multiplexed by `conn_id`.

---

## Architecture notes

**Why no root on Android?**
`VpnService` is a standard Android API that lets an unprivileged app install a default route through a virtual TUN interface. The OS shows a consent dialog on first use; after that the app can start the VPN silently.

**Why `Os.socket()` instead of `new Socket()`?**
`new Socket()` creates its native file descriptor lazily — the `socket()` syscall only runs at the first `connect()` or `bind()` call. `VpnService.protect(socket)` needs to read the fd before connect to avoid a routing loop, so it sees `fd = -1` and returns false. `Os.socket()` (Android system call wrapper) creates the fd immediately. We then extract the int fd via reflection on `FileDescriptor.descriptor` and call `protect(int)`.

**Why ADB reverse tunnel?**
Zero kernel drivers, zero phone-side configuration, works on any Android device with USB debugging enabled. The ADB tunnel is a transparent byte pipe — the relay and client don't know or care that USB is the transport. Replacing it with a custom USB protocol (Android Open Accessory) is a planned future improvement.

**Why a TUN device on the relay side instead of a userspace TCP stack?**
The kernel handles all TCP/IP complexity — connection tracking, retransmission, congestion control, MSS negotiation. The relay just moves raw IP packets between the ADB TCP connection and the TUN fd. This keeps the relay code simple and gives correct behaviour for free.

---

## Debugging

```bash
# Watch all OpenTether log tags on Android
adb logcat \
  -s "OT/VpnService:D" \
  -s "OT/TunReader:D" \
  -s "OT/TunWriter:D" \
  -s "OT/UsbTunnelClient:D" \
  -s "OT/MainActivity:D" \
  -s "AndroidRuntime:E"

# Watch packets on the relay TUN interface
sudo tcpdump -i ot0 -n

# Watch packets leaving on the real interface (confirm NAT is working)
sudo tcpdump -i eth0 -n host 8.8.8.8

# Verify NAT rule is hitting
sudo iptables -t nat -L POSTROUTING -v

# Test the relay without an Android device
go run tools/mock_android/main.go
# Sends a real DNS query through the relay and prints the response
```

**Common problems**

| Symptom | Cause | Fix |
|---------|-------|-----|
| `ECONNREFUSED` on phone | Relay not running or ADB tunnel not set up | Start relay first, then `adb reverse tcp:8765 tcp:8765` |
| `protect() returned false` | `INTERNET` permission missing | Confirm `AndroidManifest.xml` has `android.permission.INTERNET` |
| WhatsApp works, browser doesn't | DNS black hole | `addDnsServer` must point to a real resolver (`8.8.8.8`), not the relay TUN IP |
| VPN stops when screen locks | Android battery optimisation | Disable battery optimisation for OpenTether in phone settings |
| No packets on `ot0` | ADB tunnel dropped | Re-run `adb reverse tcp:8765 tcp:8765` |

---

## Performance

Tested on USB 2.0 with a Pixel 6 and an x86-64 Linux host:

| Metric | Measured |
|--------|----------|
| Throughput (large file download) | 18–35 Mbps |
| Latency overhead vs direct connection | +3–8 ms |
| Concurrent connections | 500+ (limited by Go goroutine scheduler) |
| Memory usage (relay, idle) | ~8 MB |
| Memory usage (relay, 100 active connections) | ~25 MB |

USB 3.0 and a faster host will push throughput higher. The primary bottleneck at these speeds is the ADB protocol framing overhead, not the relay code.

---

## Security

- The relay binds to `127.0.0.1` only. It is not reachable from the network — only through the ADB reverse tunnel, which requires physical USB access.
- Payload lengths are validated on both sides (max 65535 bytes). Oversized frames are rejected before any allocation.
- The relay removes all iptables rules and routes on shutdown (SIGINT, SIGTERM, or panic recovery). Abrupt kill (`SIGKILL`) will leave rules in place — run `sudo iptables -t nat -L` to inspect and remove manually if needed.
- The Android app requests only `INTERNET`, `BIND_VPN_SERVICE`, and `FOREGROUND_SERVICE` permissions.

---

## Planned improvements

- **Auto ADB tunnel setup** — relay detects phone connection and runs `adb reverse` automatically
- **Android Open Accessory transport** — replace ADB with direct USB, removing the manual `adb reverse` step
- **UDP optimisation** — reduce per-packet overhead for DNS and media streams
- **Stats UI** — bytes/sec, active connections, and relay RTT shown in the Android app
- **IPv6 full support** — NAT64/DNS64 for phones that prefer IPv6
- **Split tunnelling** — route only selected apps through the tunnel
- **Auto-reconnect on cable unplug** — redetect and re-establish without user interaction

---

## Contributing

Issues and pull requests are welcome. The codebase is intentionally kept small and readable — if a change makes a file significantly harder to follow, it probably belongs in a separate module.

For large changes, open an issue first to discuss the approach.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
