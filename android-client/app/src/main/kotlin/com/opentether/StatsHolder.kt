package com.opentether

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide stats shared between [OpenTetherVpnService] (ticker)
 * and [UsbTunnelClient] (byte counter) and [MainActivity] (reader).
 *
 * Byte counters live here so they can be incremented by the tunnel
 * and sampled by the service ticker independently of relay connection state.
 */
object StatsHolder {

    // ── Per-second window counters (reset every tick) ─────────────────────
    val bytesDownSec = AtomicLong(0)
    val bytesUpSec   = AtomicLong(0)

    // ── Cumulative totals (reset only on setRunning(false)) ───────────────
    val totalDown = AtomicLong(0)
    val totalUp   = AtomicLong(0)

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
            _stats.value = VpnStats()
        }
    }

    /**
     * Called by the service ticker every second.
     * Drains the per-second windows and publishes a new [VpnStats].
     */
    fun tick(activeConnections: Int = 0, connections: List<ConnectionEntry> = emptyList()) {
        val dl = bytesDownSec.getAndSet(0)
        val ul = bytesUpSec.getAndSet(0)
        _stats.value = VpnStats(
            downloadBytesPerSec = dl,
            uploadBytesPerSec   = ul,
            totalDownloadBytes  = totalDown.get(),
            totalUploadBytes    = totalUp.get(),
            activeConnections   = activeConnections,
            connections         = connections,
        )
    }
}