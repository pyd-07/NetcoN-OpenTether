# OpenTether
### Built by a student to solve the "no easy hotspot on Linux" problem. Built with Claude and GPT.

**v0.9.1** — Reverse USB tethering for Android. Route your phone's internet traffic through your Linux PC via USB — no root, no Wi-Fi, just a cable.

```
Android app  →  VpnService (TUN)  →  USB (ADB or AOA)  →  Go relay  →  internet
```

Similar to [Gnirehtet](https://github.com/Genymobile/gnirehtet) and [Tetrd](https://tetrd.app), but fully open-source, modular, and written to be read and extended.

---

## What's new in v0.9.1

| Feature | Details |
|---|---|
| **Auto ADB tunnel** | Relay watches `adb devices` and runs `adb reverse` automatically on every connect/reconnect |
| **AOA transport** | Direct USB pipe without ADB — phone opens app automatically after one-time consent |
| **UDP / DNS batching** | Dual-flush strategy cuts syscall count ~38 % for DNS-heavy workloads |
| **IPv6 full NAT** | `ip6tables MASQUERADE` so IPv6 traffic captured by the VPN reaches the internet |

---

## How it works

Android's `VpnService` API lets an unprivileged app intercept all network traffic by installing a virtual TUN interface. OpenTether reads raw IP packets off that TUN, wraps them in a 12-byte binary protocol (OTP — OpenTether Protocol), and sends them to a relay process on the PC over USB.

The relay writes the packets into a Linux TUN device and lets the kernel route them to the internet via `iptables MASQUERADE`. Responses travel back the same pipe.

### Two transport options

**ADB transport (default)** — TCP over the ADB reverse tunnel:
- Requires USB debugging enabled on Android
- Relay runs `adb reverse tcp:8765 tcp:8765` automatically — no manual step needed
- Works on all Android devices with USB debugging

**AOA transport (optional)** — direct USB bulk pipe, no ADB at all:
- No USB debugging required after the initial app install
- One-time consent dialog on first connect; after that plugging in the cable opens the app and starts the VPN automatically
- Requires building the relay with `-tags aoa` and installing `libusb-1.0-0-dev`

```
┌─────────────────────────────┐         USB          ┌──────────────────────────┐
│         Android             │ ◄──────────────────► │           PC             │
│                             │     ADB or AOA       │                          │
│  App traffic                │                      │  Go relay                │
│    ↓                        │                      │    ↓                     │
│  VpnService (TUN iface)     │                      │  TUN device (ot0)       │
│    ↓                        │                      │    ↓                     │
│  TunReader coroutine        │                      │  iptables MASQUERADE    │
│    ↓                        │                      │    ↓                     │
│  UsbTunnelClient ───────────────────────────────►  │  internet               │
│  (or AoaTunnelClient)       │                      │    ↓                     │
│    ↑                        │                      │  relay → Android        │
│  TunWriter coroutine ◄──────────────────────────── │                          │
└─────────────────────────────┘                      └──────────────────────────┘
```

---

## Requirements

**PC**
- Linux (kernel 3.17+ for TUN/TAP support)
- Go 1.22+
- `ip`, `iptables`, `ip6tables` on PATH
- Root or `CAP_NET_ADMIN` + `CAP_NET_RAW`
- ADB *(ADB transport only)*
- `libusb-1.0-0-dev` *(AOA transport only)*

**Android**
- Android 8.0+ (API 26)
- USB debugging *(ADB transport only — not needed for AOA after first install)*
- No root required

---

## Quick start

### One-command setup (recommended)

```bash
git clone https://github.com/pyd-07/NetcoN-OpenTether.git
cd NetcoN-OpenTether

sudo ./setup.sh           # ADB transport — USB debugging required
sudo ./setup.sh --aoa     # AOA transport — no USB debugging after first install
```

`setup.sh` installs dependencies, builds everything, installs the APK, and starts the relay. See `sudo ./setup.sh --help` for all options including `--build-only`.

### Manual setup

#### 1 — Build the relay

```bash
# ADB transport (no extra dependencies)
go build -o relay .

# AOA transport (requires libusb)
sudo apt install libusb-1.0-0-dev
go build -tags aoa -o relay .
```

#### 2 — Build and install the Android app

```bash
cd android-client
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ..
```

#### 3 — Start the relay

```bash
sudo ./relay
```

The relay creates TUN interface `ot0`, configures NAT, and starts the ADB watcher which runs `adb reverse tcp:8765 tcp:8765` automatically when your phone connects. No manual tunnel setup needed.

#### 4 — Start the VPN

Open OpenTether on the phone and tap **Start VPN**. Accept the system VPN consent dialog on first run.

### Stop

Tap **Stop VPN** on the phone, then `Ctrl+C` in the relay terminal. All `iptables` rules are removed on clean exit.

---

## AOA transport (no USB debugging needed)

### Setup

```bash
# Build with AOA support
sudo apt install libusb-1.0-0-dev
go build -tags aoa -o relay .

# Install the app once (USB debugging required just for this step)
adb install -r android-client/app/build/outputs/apk/debug/app-debug.apk

# Start the relay
sudo ./relay
```

Plug in the cable. The relay sends AOA identification strings to the phone. Android shows **"Open OpenTether for this accessory?"** — tap **OK once**.

After that:
- USB debugging can be disabled permanently
- Plugging in the cable opens the app and starts the VPN automatically
- No commands needed, ever

---

## Relay flags

```
-listen        TCP address to listen on        (default: 127.0.0.1:8765)
-tun           TUN interface name              (default: ot0)
-tun-addr      Relay-side TUN IP               (default: 10.0.0.2)
-tun-prefix    Prefix length for tun-addr      (default: 24)
-android-ip    Android client VPN IP           (default: 10.0.0.1)
-out-iface     Outbound interface for NAT      (default: auto-detect)
-mtu           MTU for TUN and buffers         (default: 1500)
-no-adb-watch  Disable automatic adb reverse   (default: false)
-v             Verbose per-packet logging
```

Examples:

```bash
sudo ./relay -out-iface wlan0 -v       # explicit interface, verbose
sudo ./relay -listen 127.0.0.1:9000    # custom port
sudo ./relay -no-adb-watch             # manage adb reverse manually
```

---

## Project structure

```
opentether/
├── main.go                              Entry point and flag parsing
├── go.mod / go.sum
├── setup.sh                             One-command setup script
│
├── relay/
│   ├── config.go                        Config struct
│   ├── protocol.go                      OTP frame encode / decode
│   ├── protocol_test.go                 Round-trip + oversized-frame tests
│   ├── tun_linux.go                     TUN device via TUNSETIFF ioctl
│   ├── nat_linux.go                     ip + ip6tables setup and LIFO cleanup
│   ├── session.go                       Bridge goroutines + FlushWriter batching
│   ├── server.go                        TCP listener + ADB watcher
│   ├── adb_watcher.go                   Auto adb reverse on device connect  ← NEW
│   ├── udp_batcher.go                   FlushWriter dual-flush strategy     ← NEW
│   ├── aoa_linux.go   (build: -tags aoa) AOA USB transport                 ← NEW
│   └── log.go                           Levelled logging
│
├── android-client/
│   └── app/src/main/
│       ├── AndroidManifest.xml          USB accessory intent filter         ← UPDATED
│       ├── res/xml/
│       │   └── usb_accessory_filter.xml AOA identification matcher          ← NEW
│       └── kotlin/com/opentether/
│           ├── Constants.kt
│           ├── MainActivity.kt
│           ├── StatsHolder.kt / VpnStats.kt
│           ├── model/OtpFrame.kt
│           ├── view/ThroughputChartView.kt
│           ├── vpn/
│           │   ├── OpenTetherVpnService.kt
│           │   ├── TunReader.kt
│           │   └── TunWriter.kt
│           └── tunnel/
│               ├── PacketEncoder.kt
│               ├── PacketDecoder.kt
│               ├── UsbTunnelClient.kt   UDP batching added                  ← UPDATED
│               └── AoaTunnelClient.kt   AOA USB transport                   ← NEW
│
└── tools/
    └── mock_android/main.go             Sends a real DNS query through relay (no phone needed)
```

---

## OTP protocol

Every message is a framed OTP packet. Header is 12 bytes, big-endian.

```
 0       4       8  9  10      12
 |conn_id|pay_len|ty|fl|reservd|...payload...

conn_id     uint32   Logical connection ID
pay_len     uint32   Payload length (0 valid for control frames)
ty          uint8    Message type
fl          uint8    Flags bitmask
reserved    uint16   Must be zero
payload     bytes    Raw IP packet, or UTF-8 string for MSG_ERROR
```

| Type | Value | Direction | Meaning |
|---|---|---|---|
| MSG_DATA | 0x01 | both | Raw IPv4 or IPv6 packet |
| MSG_CONNECT | 0x02 | Android → relay | New connection intent |
| MSG_CLOSE | 0x03 | both | Graceful teardown |
| MSG_ERROR | 0x04 | both | Error (payload = UTF-8 string) |
| MSG_PING | 0x05 | Android → relay | Keepalive probe |
| MSG_PONG | 0x06 | relay → Android | Keepalive reply |

Payload length is validated on both sides. Frames claiming more than 65535 bytes are rejected before any allocation.

---

## UDP / DNS optimisation

Without batching, every small UDP frame (DNS query ~100 B, QUIC ACK ~40 B) costs one `write()` syscall and one TCP segment. At hundreds per second this is measurable.

**Dual-flush strategy (relay `FlushWriter` + Android `BufferedOutputStream`):**

- Packets **≥ 512 B** → flushed immediately. TCP bulk transfers and video see zero added latency.
- Packets **< 512 B** → buffered in 32 KB, flushed every 1–2 ms by a background goroutine/coroutine.

Result: ~38 % fewer `write()` syscalls for DNS-heavy workloads. Maximum added latency for small packets: 2 ms (measured average < 0.5 ms).

---

## Architecture notes

**Why no root on Android?** `VpnService` is a standard Android API. The system shows a consent dialog once; after that the app can start the VPN silently.

**Why `Os.socket()` instead of `new Socket()`?** `new Socket()` creates its fd lazily — at the first `connect()`. `VpnService.protect(socket)` needs the fd before connect to mark it as exempt from VPN routing. `Os.socket()` creates the fd immediately; we extract the int fd via reflection and call `protect(int)`.

**Why ADB reverse tunnel?** Zero kernel drivers, transparent byte pipe, works on any Android device with USB debugging. The relay and app don't know or care that USB is the transport.

**Why AOA?** Removes the USB debugging requirement entirely. After one consent dialog, plugging in the cable is the entire user action.

**Why a TUN device on the relay instead of a userspace TCP stack?** The kernel handles all TCP/IP complexity. The relay just moves raw IP packets between the USB connection and the TUN fd — simple code, correct behaviour.

**Why `FlushWriter`?** A VPN needs two simultaneous flush policies: large packets (TCP bulk, video) must flush immediately; small packets (DNS, QUIC) should batch. `FlushWriter` implements both with a size threshold and a background ticker.

---

## Debugging

```bash
# Android logs
adb logcat \
  -s "OT/VpnService:D" "OT/TunReader:D" "OT/TunWriter:D" \
  -s "OT/UsbTunnelClient:D" "OT/AoaTunnelClient:D" \
  -s "OT/MainActivity:D" "AndroidRuntime:E"

# Watch relay TUN packets
sudo tcpdump -i ot0 -n

# Confirm NAT is applying
sudo tcpdump -i eth0 -n src 10.0.0.1

# Check iptables rules are installed
sudo iptables  -t nat -L POSTROUTING -v -n
sudo ip6tables -t nat -L POSTROUTING -v -n

# Test relay without a phone (sends a real DNS query)
go run tools/mock_android/main.go
```

---

## Common problems

| Symptom | Cause | Fix |
|---|---|---|
| `ECONNREFUSED` on phone | Relay not running | `sudo ./relay` — it sets up `adb reverse` automatically |
| `protect() returned false` | `INTERNET` permission missing | Check `AndroidManifest.xml` |
| No internet despite "Connected" | DNS misconfiguration | `addDnsServer` must be `8.8.8.8`, not the TUN IP |
| VPN stops when screen locks | Battery optimisation | Settings → Apps → OpenTether → Battery → Unrestricted |
| No packets on `ot0` | ADB tunnel dropped | Reconnect cable — relay retries `adb reverse` automatically |
| AOA: device not recognised | libusb not installed | `apt install libusb-1.0-0-dev`, rebuild `-tags aoa` |
| AOA: no consent dialog | Accessory strings mismatch | `usb_accessory_filter.xml` must match `aoaStrings` in `aoa_linux.go` |
| Build error on `usb_accessory_filter.xml` | Missing `xmlns:android` | Add `xmlns:android="http://schemas.android.com/apk/res/android"` to `<usb-accessory>` |

---

## Performance

Tested on USB 2.0, Pixel 6, x86-64 Debian 12 host:

| Metric | v0.9.0 | v0.9.1 |
|---|---|---|
| Throughput (large download) | 18–35 Mbps | 18–35 Mbps |
| Latency overhead | +3–8 ms | +3–8 ms |
| `write()` syscalls (DNS-heavy) | baseline | ~38 % fewer |
| DNS p99 latency | baseline | unchanged |
| Memory (idle) | ~8 MB | ~8 MB |
| Memory (100 connections) | ~25 MB | ~25 MB |

---

## Security

- Relay binds to `127.0.0.1` only — unreachable from the network without physical USB access.
- Payload lengths validated on both sides; oversized frames rejected before allocation.
- All `iptables` / `ip6tables` rules removed on clean exit. `SIGKILL` leaves rules — inspect with `sudo iptables -t nat -L`.
- Android permissions: `INTERNET`, `BIND_VPN_SERVICE`, `FOREGROUND_SERVICE`. AOA adds `android.hardware.usb.accessory` (marked `required=false`).

---

## Planned improvements

- ~~**Auto ADB tunnel**~~ ✅ v0.9.1
- ~~**AOA transport**~~ ✅ v0.9.1
- ~~**UDP / DNS optimisation**~~ ✅ v0.9.1
- **Live RTT display** — show relay round-trip time in the Android stats UI
- **Split tunnelling** — route only selected apps through the tunnel
- **Auto-reconnect on unplug** — redetect and re-establish without user action
- **AOA arm64 release binary** — needs cross-compilation toolchain for CGO + libusb

---

## Contributing

Issues and pull requests are welcome. The codebase is intentionally small — if a change makes a file harder to follow, it probably belongs in a separate module. For large changes, open an issue first.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).