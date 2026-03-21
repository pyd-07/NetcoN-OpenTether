package com.opentether.vpn

import android.util.Log
import com.opentether.Constants
import com.opentether.StatsHolder
import com.opentether.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException

private const val TAG = "OT/TunReader"

/**
 * Reads raw IP packets from the VPN TUN file descriptor one at a time
 * and pushes each into [outbound].
 *
 * The kernel guarantees that one read() call on a TUN fd returns exactly
 * one complete IP packet — never a partial packet, never two at once.
 * This makes the read loop very simple: no framing, no buffering needed.
 *
 * Backpressure: if [outbound] is full, send() suspends this coroutine
 * until the consumer catches up. No packets are dropped.
 */
class TunReader(
    private val fd: FileDescriptor,
    private val outbound: Channel<ByteArray>,
) {
    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        AppLogger.i(TAG, "started")

        // Reused across reads — copyOf(n) is called before send() so
        // the buffer is safe to overwrite on the next iteration.
        val buffer = ByteArray(Constants.VPN_MTU + 4)
        val stream = FileInputStream(fd)

        try {
            while (isActive) {
                val n = try {
                    stream.read(buffer)
                } catch (e: IOException) {
                    if (!isActive) break  // normal shutdown, not an error
                    AppLogger.e(TAG, "read failed: ${e.message}")
                    break
                }

                if (n <= 0) continue

                // Copy only the bytes that were read.
                // We must copy because the buffer is reused next iteration
                // and the channel consumer may not have read this yet.
                val packet = buffer.copyOf(n)

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    val version = (packet[0].toInt() and 0xFF) shr 4
                    val proto   = if (version == 4 && packet.size > 9)
                                      packet[9].toInt() and 0xFF else -1
                    val name    = when (proto) { 6->"TCP" 17->"UDP" 1->"ICMP" else->"proto=$proto" }
                    AppLogger.d(TAG, "→ TUN ${packet.size}B  IPv$version  $name")
                }

                StatsHolder.recordOutboundPacket(packet)
                outbound.send(packet)
            }
        } finally {
            // Do NOT close stream — the fd is owned by VpnService.
            AppLogger.i(TAG, "stopped")
        }
    }
}
