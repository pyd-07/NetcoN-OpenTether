package com.opentether.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "OT/TunWriter"

/**
 * Drains [inbound] and writes each raw IP packet into the TUN fd.
 *
 * Writing a packet to the TUN fd is equivalent to the packet arriving
 * on a real network interface — the Android kernel reads the destination
 * IP and port from the IP header and delivers it to the waiting socket.
 *
 * One write() call = one IP packet. The kernel handles it atomically.
 */
class TunWriter(
    private val fd: FileDescriptor,
    private val inbound: Channel<ByteArray>,
) {
    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        Log.i(TAG, "started")

        val stream = FileOutputStream(fd)

        try {
            // for-each on a Channel suspends between packets — zero CPU when idle.
            for (packet in inbound) {
                if (!isActive) break

                try {
                    stream.write(packet)
                    Log.d(TAG, "← TUN inject ${packet.size}B")
                } catch (e: IOException) {
                    if (!isActive) break
                    Log.e(TAG, "write failed: ${e.message}")
                    // One bad packet doesn't kill the writer — keep going.
                }
            }
        } finally {
            // Do NOT close stream — the fd is owned by VpnService.
            Log.i(TAG, "stopped")
        }
    }
}
