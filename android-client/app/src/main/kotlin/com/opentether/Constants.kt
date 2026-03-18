package com.opentether

object Constants {

    // ── Network ───────────────────────────────────────────────────────────────

    const val VPN_CLIENT_IP    = "10.0.0.1"
    const val VPN_RELAY_IP     = "10.0.0.2"
    const val VPN_DNS_SERVER   = "8.8.8.8" 
    const val VPN_PREFIX       = 24
    const val VPN_MTU          = 1400
    const val VPN_SESSION_NAME = "OpenTether"

    // ── Tunnel ────────────────────────────────────────────────────────────────

    const val RELAY_HOST          = "127.0.0.1"
    const val RELAY_PORT          = 8765
    const val CONNECT_TIMEOUT_MS  = 5_000L
    const val RECONNECT_DELAY_MS  = 2_000L

    // ── OTP Protocol ─────────────────────────────────────────────────────────

    const val FRAME_HEADER_SIZE = 12

    const val MSG_DATA:    Byte = 0x01
    const val MSG_CONNECT: Byte = 0x02
    const val MSG_CLOSE:   Byte = 0x03
    const val MSG_ERROR:   Byte = 0x04
    const val MSG_PING:    Byte = 0x05
    const val MSG_PONG:    Byte = 0x06

    const val FLAG_FIN: Byte = 0x01
    const val FLAG_SYN: Byte = 0x02
    const val FLAG_RST: Byte = 0x04

    // ── Channels ──────────────────────────────────────────────────────────────

    const val OUTBOUND_CHANNEL_CAPACITY = 128
    const val INBOUND_CHANNEL_CAPACITY  = 128

    // ── Notification ─────────────────────────────────────────────────────────

    const val NOTIFICATION_CHANNEL_ID = "opentether_vpn"
    const val NOTIFICATION_ID         = 1
}
