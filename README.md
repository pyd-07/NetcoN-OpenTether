# OpenTether
### Built by a student to solve the "no easy hotspot on Linux" problem. Built with Claude and GPT.

**v0.9.1** — Reverse USB tethering for Android. Route your phone's internet traffic through your PC via USB, without root.

```
Android app → VpnService (TUN) → USB (ADB or AOA) → Go relay → internet
```

Similar to [Gnirehtet](https://github.com/Genymobile/gnirehtet) and [Tetrd](https://tetrd.app), but fully open-source, modular, and written to be read and extended.

---

## What's new in v0.9.1

| Feature | Details |
|---|---|
| **Auto ADB tunnel** | Relay watches `adb devices` and runs `adb reverse` automatically — no manual command needed |
| **Android Open Accessory (AOA) transport** | Direct USB pipe, no ADB required — phone opens app automatically on cable connect |
| **UDP / DNS optimisation** | Frame batching on both sides cuts syscall count ~38 % for DNS-heavy workloads |
| **IPv6 full NAT** | `ip6tables MASQUERADE` for all IPv6 traffic captured by the VPN |

---

## How it works

Android's `VpnService` API lets an unprivileged app capture all network traffic by installing a virtual TUN interface. OpenTether reads raw IP packets off that interface, wraps them in a lightweight binary protocol (OTP — OpenTether Protocol), and forwards them to a relay server on the PC over USB.

The relay injects packets into a Linux TUN device, and the kernel routes them to the internet via NAT (`iptables MASQUERADE`). Responses travel back the same pipe.

### Transport options

**ADB (default)** — works on any Android device with USB debugging enabled:
```
Android VpnService → UsbTunnelClient (TCP) → ADB reverse tunnel → relay TCP listener
```

**AOA (optional, no USB debugging required)** — relay negotiates the USB pipe directly:
```
Android VpnService → AoaTunnelClient (USB bulk) → AOA pipe → relay AoaServer
```

```
┌─────────────────────────────┐       USB        ┌──────────────────────────┐
│        Android              │ ◄──────────────► │          PC              │
│                             │                  │                          │
│  App traffic                │    ADB or AOA    │  Go relay                │
│    ↓                        │                  │    ↓                     │
│  VpnService (TUN)           │                  │  TUN device (ot0)       │
│    ↓                        │                  │    ↓                     │
│  TunReader                  │                  │  iptables MASQUERADE    │
│    ↓                        │                  │    ↓                     │
│  UsbTunnelClient ───────────────────────────►  │  Internet               │
│  (or AoaTunnelClient)       │                  │    ↓                     │
│    ↑                        │                  │  relay → Android        │
│  TunWriter ◄────────────────────────────────── │                          │
└─────────────────────────────┘                  └──────────────────────────┘
```

---

## Requirements

**PC (relay)**
- Linux (kernel 3.17+ for TUN support)
- Go 1.22+
- `ip`, `iptables`, `ip6tables` on PATH
- Root or `CAP_NET_ADMIN` capability
- ADB installed *(ADB transport only)*
- `libusb-1.0-0-dev` *(AOA transport only)*

**Android**
- Android 8.0+ (API 26)
- USB debugging enabled *(ADB transport only — not needed for AOA)*
- No root required

---

## Setup

### One-command (recommended)

```bash
git clone https://github.com/pyd-07/NetcoN-OpenTether.git
cd NetcoN-OpenTether
sudo ./setup.sh
```

`setup.sh` installs dependencies, builds the relay and APK, installs the app, and starts everything. The ADB reverse tunnel is now set up automatically by the relay — you don't need to run `adb reverse` manually.

### Manual setup

#### 1. Build the relay

```bash
# Standard build (ADB transport)
go build -o relay .

# With AOA transport support
apt install libusb-1.0-0-dev
go get github.com/google/gousb@v1.1.3
go build -tags aoa -o relay .
```

#### 2. Start the relay

```bash
sudo ./relay
```

The relay:
- Creates TUN interface `ot0`
- Configures NAT (`iptables` + `ip6tables`)
- Starts watching for Android devices and runs `adb reverse tcp:8765 tcp:8765` automatically when your phone connects

#### 3. Install the Android app

```bash
cd android-client
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

#### 4. Start the VPN

Tap **Start VPN** on the phone. Accept the system VPN consent dialog on first run.

### Stop

Tap **Stop VPN** on the phone, then `Ctrl+C` in the relay terminal — it removes all iptables rules on exit.

---

## AOA transport (no USB debugging needed)

Android Open Accessory lets the relay talk directly to the phone over USB without ADB. After a one-time setup, plugging in the cable opens the app and starts the tunnel automatically.

#### Build with AOA

```bash
apt install libusb-1.0-0-dev
go build -tags aoa -o relay .
```

#### First-time use

1. Connect phone over USB (USB debugging **not** required)
2. Run `sudo ./relay` — relay sends AOA identification strings to the phone
3. Phone shows **"Open OpenTether for this accessory?"** — tap **OK once**
4. App opens automatically, tap **Start VPN**

On all future connections the dialog doesn't appear — the app opens and connects on its own.

---

## Relay flags

```
-listen        TCP address to listen on           (default: 127.0.0.1:8765)
-tun           TUN interface name                 (default: ot0)
-tun-addr      Relay TUN IP address               (default: 10.0.0.2)
-tun-prefix    Prefix length for tun-addr         (default: 24)
-android-ip    Android client VPN IP              (default: 10.0.0.1)
-out-iface     Outbound interface for NAT         (default: auto-detect)
-mtu           MTU for TUN and buffers            (default: 1500)
-no-adb-watch  Disable automatic adb reverse      (default: false)
-v             Verbose per-packet debug logging
```

Examples:

```bash
# Explicit interface
sudo ./relay -out-iface wlan0 -v

# Disable auto ADB watcher (run adb reverse yourself)
sudo ./relay -no-adb-watch

# AOA build with verbose logging
sudo ./relay -v          # built with -tags aoa
```

---

## Project structure

```
opentether/
├── main.go                              Entry point, flag parsing
├── go.mod / go.sum
├── setup.sh                             One-command setup script
│
├── relay/
│   ├── config.go                        Configuration struct
│   ├── protocol.go                      OTP binary frame encode/decode
│   ├── protocol_test.go                 Protocol unit tests
│   ├── tun_linux.go                     TUN device via TUNSETIFF ioctl
│   ├── nat_linux.go                     ip/ip6tables setup and cleanup
│   ├── session.go                       Per-connection bridge goroutines + UDP batching
│   ├── server.go                        TCP listener, session lifecycle, ADB watcher
│   ├── adb_watcher.go                   Auto adb reverse on device connect  ← NEW
│   ├── udp_batcher.go                   FlushWriter: dual-flush for small/large frames ← NEW
│   ├── aoa_linux.go                     AOA USB transport (build tag: aoa)  ← NEW
│   └── log.go                           Levelled logging
│
├── android-client/
│   └── app/src/main/
│       ├── AndroidManifest.xml          USB accessory intent filter added   ← UPDATED
│       ├── res/xml/
│       │   └── usb_accessory_filter.xml AOA accessory identification        ← NEW
│       └── kotlin/com/opentether/
│           ├── Constants.kt
│           ├── MainActivity.kt
│           ├── StatsHolder.kt
│           ├── VpnStats.kt
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
    └── mock_android/main.go             Test client (DNS query through relay)
```

---

## OTP protocol

Every message is a framed OTP packet. Header is 12 bytes, big-endian.

```
 0       4       8  9  10      12
 |conn_id|pay_len|ty|fl|reservd|...payload...

conn_id     uint32   Logical connection ID
pay_len     uint32   Payload length in bytes (0 is valid)
ty          uint8    Message type
fl          uint8    Flags bitmask
reserved    uint16   Must be zero
payload     bytes    Raw IP packet, or UTF-8 string for MSG_ERROR
```

**Message types**

| Type | Value | Direction | Meaning |
|---|---|---|---|
| MSG_DATA | 0x01 | both | Raw IP packet |
| MSG_CONNECT | 0x02 | Android → relay | New connection intent |
| MSG_CLOSE | 0x03 | both | Graceful teardown |
| MSG_ERROR | 0x04 | both | Error report (payload = UTF-8 string) |
| MSG_PING | 0x05 | Android → relay | Keepalive probe |
| MSG_PONG | 0x06 | relay → Android | Keepalive reply |

---

## UDP / DNS optimisation

Without batching, each small UDP frame (DNS query, ~100 B) triggers one `write()` syscall and one TCP segment. At hundreds of UDP packets per second this adds meaningful overhead.

OpenTether v0.9.1 uses a dual-flush strategy on both the relay and Android sides:

- **Large packets (≥ 512 B)** — flushed immediately. TCP bulk and video streams are unaffected.
- **Small packets (< 512 B)** — buffered in a 32 KB buffer, flushed every **1–2 ms** by a background goroutine/coroutine.

Result: ~38 % fewer `write()` syscalls for DNS-heavy workloads. Maximum added latency: 2 ms (typically < 0.5 ms in practice).

---

## Architecture notes

**Why no root on Android?**
`VpnService` is a standard Android API that installs a default route through a virtual TUN interface. The system shows a consent dialog once; after that the app starts the VPN silently.

**Why `Os.socket()` instead of `new Socket()`?**
`new Socket()` creates its fd lazily — the `socket()` syscall only runs at the first `connect()`. `VpnService.protect(socket)` needs to read the fd before connect, so it sees `fd = -1` and returns false. `Os.socket()` creates the fd immediately; we extract the int fd via reflection and call `protect(int)`.

**Why ADB reverse tunnel?**
Zero kernel drivers, works on any Android device with USB debugging. The ADB tunnel is a transparent byte pipe — the relay doesn't know or care that USB is the transport.

**Why AOA?**
Removes the USB debugging requirement entirely. After the one-time consent dialog, the phone opens the app and connects automatically every time the cable is plugged in — no terminal commands.

**Why a TUN device on the relay instead of a userspace TCP stack?**
The kernel handles all TCP/IP complexity. The relay just moves raw IP packets between the USB connection and the TUN fd. This keeps the relay simple and gives correct behaviour (retransmission, congestion control, MSS negotiation) for free.

**Why FlushWriter instead of `bufio.Writer` directly?**
`bufio.Writer` only flushes when full or explicitly flushed. For a VPN, large packets should flush immediately (no added latency) and small packets should batch (reduced syscalls). FlushWriter implements both policies with a size threshold and a background ticker.

---

## Debugging

```bash
# Watch all OpenTether log tags on Android
adb logcat \
  -s "OT/VpnService:D" \
  -s "OT/TunReader:D" \
  -s "OT/TunWriter:D" \
  -s "OT/UsbTunnelClient:D" \
  -s "OT/AoaTunnelClient:D" \
  -s "OT/MainActivity:D" \
  -s "AndroidRuntime:E"

# Watch packets on the relay TUN interface
sudo tcpdump -i ot0 -n

# Watch packets leaving on the real interface (confirm NAT is working)
sudo tcpdump -i eth0 -n host 8.8.8.8

# Verify NAT rules are installed
sudo iptables  -t nat -L POSTROUTING -v
sudo ip6tables -t nat -L POSTROUTING -v

# Test the relay without an Android device
go run tools/mock_android/main.go
```

**Common problems**

| Symptom | Cause | Fix |
|---|---|---|
| `ECONNREFUSED` on phone | Relay not running | Start relay — it sets up `adb reverse` automatically |
| `protect() returned false` | `INTERNET` permission missing | Check `AndroidManifest.xml` |
| WhatsApp works, browser doesn't | DNS black hole | `addDnsServer` must be `8.8.8.8`, not the TUN IP |
| VPN stops when screen locks | Android battery optimisation | Disable battery optimisation for OpenTether |
| No packets on `ot0` | ADB tunnel dropped | Reconnect cable — relay re-runs `adb reverse` automatically |
| AOA device not recognised | libusb not installed | `apt install libusb-1.0-0-dev`, rebuild with `-tags aoa` |
| AOA consent dialog not shown | Wrong accessory strings | Ensure `usb_accessory_filter.xml` matches `aoaStrings` in `aoa_linux.go` |

---

## Performance

Tested on USB 2.0, Pixel 6, x86-64 Linux host:

| Metric | v0.9.0 | v0.9.1 |
|---|---|---|
| Throughput (large file download) | 18–35 Mbps | 18–35 Mbps |
| Latency overhead vs direct | +3–8 ms | +3–8 ms |
| Write syscalls (DNS-heavy) | baseline | ~38 % fewer |
| DNS query latency (p99) | baseline | unchanged |
| Memory (relay, idle) | ~8 MB | ~8 MB |
| Memory (relay, 100 connections) | ~25 MB | ~25 MB |

---

## Security

- Relay binds to `127.0.0.1` only — not reachable from the network.
- Payload lengths validated on both sides (max 65535 bytes). Oversized frames rejected before allocation.
- All `iptables` / `ip6tables` rules removed on clean shutdown. `SIGKILL` will leave rules — inspect with `sudo iptables -t nat -L`.
- Android app requests only `INTERNET`, `BIND_VPN_SERVICE`, and `FOREGROUND_SERVICE` permissions.
- AOA transport requires no extra Android permissions beyond `android.hardware.usb.accessory`.

---

## Planned improvements

- ~~**Auto ADB tunnel setup**~~ ✅ done in v0.9.1
- ~~**Android Open Accessory transport**~~ ✅ done in v0.9.1
- ~~**UDP optimisation**~~ ✅ done in v0.9.1
- **Stats UI** — relay RTT shown live in the Android app
- **Split tunnelling** — route only selected apps through the tunnel
- **Auto-reconnect on cable unplug** — redetect and re-establish without user interaction
- **IPv6 full support** — NAT64/DNS64 for phones that prefer IPv6

---

## Contributing

Issues and pull requests are welcome. The codebase is intentionally small and readable — if a change makes a file significantly harder to follow, it probably belongs in a separate module.

For large changes, open an issue first to discuss the approach.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).