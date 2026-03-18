package com.opentether

/**
 * Snapshot of all real-time stats emitted by [StatsHolder] once per second.
 */
data class VpnStats(
    val downloadBytesPerSec: Long = 0,
    val uploadBytesPerSec:   Long = 0,
    val totalDownloadBytes:  Long = 0,
    val totalUploadBytes:    Long = 0,
    val rttMs:               Int  = 0,
    val activeConnections:   Int  = 0,
    val connections:         List<ConnectionEntry> = emptyList(),
)

/**
 * One row in the "Active connections" panel.
 */
data class ConnectionEntry(
    val host:   String,
    val mbps:   Float,
    val active: Boolean,
)