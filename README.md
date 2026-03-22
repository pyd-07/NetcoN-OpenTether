# OpenTether v0.9.3

[![Version](https://img.shields.io/badge/version-v0.9.3-blue.svg)](https://github.com/pyd-07/NetcoN-OpenTether/releases/tag/v0.9.3)

---

## SECTION 1 — For Users

Reverse USB tethering for Android. Route your phone's internet traffic through your Linux PC via a USB cable — no root, no Wi-Fi, just a cable.

If you have a Linux PC with an internet connection and you want your Android phone to share it securely and quickly without relying on mobile data or Wi-Fi, OpenTether is the perfect solution for you.

### What you need
- An Android phone (Android 8.0 or newer)
- A USB cable
- A Linux PC with internet access

### Setup Path A — ADB Mode (Easiest for most people)
This path requires you to enable "USB Debugging" on your phone, which is a standard developer setting.

1. **Enable USB Debugging:** On your phone, go to Settings → Developer Options and turn on USB Debugging.
2. **Connect your phone:** Plug your phone into your Linux PC using the USB cable. Tap "Allow" if a prompt appears on your phone screen asking to allow USB debugging.
3. **Run the setup script:** Open a terminal on your Linux PC and enter:
   ```bash
   git clone https://github.com/pyd-07/NetcoN-OpenTether.git
   cd NetcoN-OpenTether
   sudo ./setup.sh
   ```
4. **Start the VPN:** Open the OpenTether app on your phone, tap **Start VPN**, and accept the Android connection request. You are now online!

### Setup Path B — AOA Mode (Plug and play without USB Debugging)
This path requires USB Debugging only once to install the app. After the first time, you can turn off USB Debugging and it becomes entirely plug and play.

1. **Install the app:** With USB Debugging temporarily enabled, connect your phone and run this on your PC:
   ```bash
   git clone https://github.com/pyd-07/NetcoN-OpenTether.git
   cd NetcoN-OpenTether
   sudo ./setup.sh --aoa
   ```
2. **Accept the prompt:** When prompted on your phone ("Open OpenTether for this accessory?"), tap **OK once**.
3. **Plug and play:** You can now safely disable USB Debugging. Every time you connect the USB cable and run `sudo ./setup.sh --aoa` (or `sudo ./relay`) on your PC, the app will automatically open and connect.

### Troubleshooting

| Symptom | What to try |
|---|---|
| No internet despite "Connected" | Your phone might have incorrect DNS settings. Ensure DNS is set to `8.8.8.8` in OpenTether, not the TUN IP. |
| VPN stops when screen locks | Android's battery saving feature might be aggressively closing the app. Go to Settings → Apps → OpenTether → Battery → set to Unrestricted. |
| "Connection refused" on phone | The relay script on your PC is likely not running. Keep the terminal open with `sudo ./setup.sh` running. |
| Nothing happens when connecting cable | Make sure your USB cable supports data transfer, not charging only. Try another port or cable. |

---

## SECTION 2 — For Developers / How It Works

OpenTether creates a virtual network bridge bypassing standard tethering drivers, implemented with a Go relay handling NAT and an Android Kotlin client capturing packets via `VpnService`. 

### Architecture Overview
The system relies on a seamless pipeline from Android's virtual networking interface to the host kernel's routing tables.
```text
Android App ↔ VpnService (TUN) ↔ USB (ADB/AOA) ↔ Go Relay ↔ TUN (ot0) ↔ iptables MASQUERADE ↔ Internet
```

### Transport Modes
- **ADB reverse TCP (`adb reverse`)**: Uses Android Debug Bridge to expose a local TCP port on the device mapped to the host's relay port. It leverages the robust ADB multiplexer but requires persistent USB Debugging.
- **AOA (Android Open Accessory) USB**: A direct USB bulk transfer pipeline. The Go relay uses `gousb` to negotiate AOA, bypassing `adbd`. The device re-enumerates as a USB accessory, triggering an Android intent matched by the app's `usb_accessory_filter.xml`.

### OTP Wire Protocol
Every packet is wrapped in the lightweight OpenTether Protocol (OTP) for stream synchronization. The header is exactly 12 bytes long and big-endian.

```text
 0       4       8  9  10      12
 |conn_id|pay_len|ty|fl|reservd|...payload...
```
- `conn_id` (4B): Logical connection ID
- `pay_len` (4B): Payload length (0 is valid for control frames)
- `ty` (1B): Message type (`0x01` DATA, `0x02` CONNECT, `0x03` CLOSE, `0x04` ERROR, `0x05` PING, `0x06` PONG)
- `fl` (1B): Flags bitmask
- `reserved` (2B): Must be zero

### Relay Session Lifecycle & Batching
1. **Device Setup**: The Go relay instantiates a local TUN interface (`ot0`) via `TUNSETIFF`.
2. **NAT Configuration**: `iptables` and `ip6tables` apply `MASQUERADE` and IPv4/IPv6 forwarding rules targeting the outbound interface. All rules are systematically cleaned up on a graceful exit.
3. **Write Strategies**: 
   - **FlushWriter** (ADB Mode): DNS queries and small UDP frames are aggressively batched with a dual-flush strategy. Packets < 512B are buffered and flushed every 1-2ms, saving ~38% on `write()` syscall overhead. Packets ≥ 512B are written immediately.
   - **DirectWriter** (AOA Mode): Writes must be strictly atomic without buffering to align exactly with USB bulk transfer boundaries.

### The AOA Framing Bug & Fix
In earlier versions, AOA mode suffered intermittent protocol desynchronization presenting as: `receiveLoop: malformed frame: OTP frame rejected: payload_length=356516592 exceeds limit 65535` and leading to total traffic loss.
*   **What broke:** The byte stream synchronization of the OTP protocol was corrupted over the direct USB accessory pipe, causing headers to be parsed starting from arbitrary body bytes.
*   **Why it broke:**
    1.  *Receiver-side Dribbling Bug:* The Android kernel's USB accessory driver drops unread bytes if a single `read()` syscall doesn't consume the entire buffered USB bulk transfer. Attempting to incrementally read the 12-byte header followed by the payload individually guaranteed data loss and desync.
    2.  *Sender-side Batching Bug:* A `BufferedOutputStream` gathered multiple OTP frames into single transfers. A 16KB read pulling from a 20KB batch threw the leftover 4KB into the void due to the precise driver behavior mentioned above. 
*   **The Fix:**
    - The sender was rewritten to use atomic, unbuffered writes. Now exactly **1 OTP Frame = 1 USB Bulk Transfer**.
    - The receiver was wrapped in a 64KB `BufferedInputStream`. This pulls the maximal allowable USB bulk transfer into userspace memory in a single syscall, guaranteeing no bytes are dropped by the kernel before decoding the frames.

### Build Instructions
#### Android App
```bash
cd android-client
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
cd ..
```

#### Go Relay
```bash
# Standard ADB mode
go build -o relay .

# AOA mode (Requires libusb)
sudo apt install libusb-1.0-0-dev
go build -tags aoa -o relay .
```

### Changelog
**v0.9.3**
- **Fixed (AOA mode):** Resolved fatal framing corruption in AOA mode. A sender-side batching bug (multiple OTP frames per USB transfer) and a receiver-side dribbling bug (AOA driver discarding unread bytes) caused `payload_length` decoding failures and dropped internet connectivity. Fixed by implementing atomic writes (`1 frame = 1 transfer`) on the sender and a 64KB `BufferedInputStream` on the receiver to pull whole transfers into userspace safely without skipping bytes.
- **Docs:** Restructured README to cleanly separate "For Users" and "For Developers / How It Works" content.

**v0.9.1**
- Auto ADB tunnel reconnect feature added.
- Added AOA transport mode.
- UDP/DNS batching using dual-flush technique for 38% fewer syscalls.
- IPv6 full NAT support added.

### Contributing
Issues and pull requests are welcome. The codebase is intentionally small — if a change makes a file harder to follow, it probably belongs in a separate module. For large changes, open an issue first.

---
License: Apache 2.0