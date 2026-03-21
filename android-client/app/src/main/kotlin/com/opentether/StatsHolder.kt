package com.opentether

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide stats shared between [OpenTetherVpnService] (ticker),
 * [UsbTunnelClient] / [AoaTunnelClient] (byte counters + RTT), and
 * [MainActivity] (reader).
 *
 * All mutable fields are atomic — safe to write from IO coroutines and read
 * from the UI thread.
 */
object StatsHolder {
    private data class ConnectionWindow(
        var downloadBytes: Long = 0,
        var uploadBytes: Long = 0,
    )

    private val connectionLock = Any()
    private val connectionWindows = linkedMapOf<String, ConnectionWindow>()

    // ── Per-second window counters (reset every tick) ─────────────────────
    val bytesDownSec = AtomicLong(0)
    val bytesUpSec   = AtomicLong(0)

    // ── Cumulative totals (reset only on setRunning(false)) ───────────────
    val totalDown = AtomicLong(0)
    val totalUp   = AtomicLong(0)

    /**
     * Last measured relay round-trip time in milliseconds.
     *
     * Updated by [UsbTunnelClient] / [AoaTunnelClient] whenever a MSG_PONG
     * is received in response to a MSG_PING sent by the ping loop.
     * 0 means no measurement has been taken yet (e.g. relay not connected).
     */
    val rttMs = AtomicInteger(0)

    // ── StateFlow observed by the UI ──────────────────────────────────────
    private val _stats = MutableStateFlow(VpnStats())
    val stats: StateFlow<VpnStats> = _stats.asStateFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun setRunning(running: Boolean) {
        isRunning = running
        if (!running) {
            bytesDownSec.set(0)
            bytesUpSec.set(0)
            totalDown.set(0)
            totalUp.set(0)
            rttMs.set(0)
            synchronized(connectionLock) { connectionWindows.clear() }
            _stats.value = VpnStats()
        }
    }

    fun recordOutboundPacket(packet: ByteArray) {
        recordPacket(packet, isOutbound = true)
    }

    fun recordInboundPacket(packet: ByteArray) {
        recordPacket(packet, isOutbound = false)
    }

    /**
     * Called by the service ticker every second.
     * Drains the per-second windows and publishes a new [VpnStats].
     */
    fun tick(activeConnections: Int = 0, connections: List<ConnectionEntry> = emptyList()) {
        val dl = bytesDownSec.getAndSet(0)
        val ul = bytesUpSec.getAndSet(0)
        val snapshot = if (activeConnections > 0 || connections.isNotEmpty()) {
            activeConnections to connections
        } else {
            snapshotConnections()
        }
        _stats.value = VpnStats(
            downloadBytesPerSec = dl,
            uploadBytesPerSec   = ul,
            totalDownloadBytes  = totalDown.get(),
            totalUploadBytes    = totalUp.get(),
            rttMs               = rttMs.get(),
            activeConnections   = snapshot.first,
            connections         = snapshot.second,
        )
    }

    private fun recordPacket(packet: ByteArray, isOutbound: Boolean) {
        val host = extractRemoteHost(packet, isOutbound) ?: return
        synchronized(connectionLock) {
            val window = connectionWindows.getOrPut(host) { ConnectionWindow() }
            if (isOutbound) {
                window.uploadBytes += packet.size.toLong()
            } else {
                window.downloadBytes += packet.size.toLong()
            }
        }
    }

    private fun snapshotConnections(): Pair<Int, List<ConnectionEntry>> {
        val entries = synchronized(connectionLock) {
            val snapshot = connectionWindows.entries.map { (host, window) ->
                ConnectionEntry(
                    host = host,
                    mbps = ((window.downloadBytes + window.uploadBytes) * 8f) / 1_000_000f,
                    active = (window.downloadBytes + window.uploadBytes) > 0L,
                    downloadBytesPerSec = window.downloadBytes,
                    uploadBytesPerSec = window.uploadBytes,
                )
            }.sortedByDescending { it.downloadBytesPerSec + it.uploadBytesPerSec }
            connectionWindows.clear()
            snapshot
        }
        return entries.size to entries
    }

    private fun extractRemoteHost(packet: ByteArray, isOutbound: Boolean): String? {
        if (packet.isEmpty()) return null
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            4 -> {
                if (packet.size < 20) return null
                val start = if (isOutbound) 16 else 12
                InetAddress.getByAddress(packet.copyOfRange(start, start + 4)).hostAddress
            }
            6 -> {
                if (packet.size < 40) return null
                val start = if (isOutbound) 24 else 8
                InetAddress.getByAddress(packet.copyOfRange(start, start + 16)).hostAddress
            }
            else -> null
        }
    }
}
