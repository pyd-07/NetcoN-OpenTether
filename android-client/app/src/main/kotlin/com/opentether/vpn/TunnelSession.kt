package com.opentether.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import com.opentether.Constants
import com.opentether.StatsHolder
import com.opentether.data.TunnelTransport
import com.opentether.logging.AppLogger
import com.opentether.tunnel.AoaTunnelClient
import com.opentether.tunnel.UsbTunnelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "OT/TunnelSession"

/** Owns all resources associated with one VPN tunnel run. */
class TunnelSession(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val transport: TunnelTransport,
) {
    private var scope: CoroutineScope? = null
    private var runtimeJob: Job? = null
    private var outbound: Channel<ByteArray>? = null
    private var inbound: Channel<ByteArray>? = null
    private var stopped = false

    fun start() {
        check(scope == null) { "TunnelSession can only be started once" }
        stopped = false

        val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val outboundChannel = Channel<ByteArray>(Constants.OUTBOUND_CHANNEL_CAPACITY)
        val inboundChannel = Channel<ByteArray>(Constants.INBOUND_CHANNEL_CAPACITY)

        scope = sessionScope
        outbound = outboundChannel
        inbound = inboundChannel

        TunReader(vpnInterface.fileDescriptor, outboundChannel).start(sessionScope)
        TunWriter(vpnInterface.fileDescriptor, inboundChannel).start(sessionScope)
        when (transport) {
            TunnelTransport.ADB -> UsbTunnelClient(outboundChannel, inboundChannel, context).start(sessionScope)
            TunnelTransport.AOA -> AoaTunnelClient(outboundChannel, inboundChannel, context).start(sessionScope)
        }

        runtimeJob = sessionScope.launch {
            while (isActive) {
                delay(1_000L)
                StatsHolder.tick()
            }
        }

        AppLogger.i(TAG, "session started (${transport.label})")
    }

    fun stop() {
        if (stopped) return
        stopped = true

        AppLogger.i(TAG, "stopping session (${transport.label})")
        runtimeJob?.cancel()
        runtimeJob = null
        scope?.cancel("Tunnel session stopped")
        scope = null
        outbound?.close()
        outbound = null
        inbound?.close()
        inbound = null
        AppLogger.i(TAG, "session stopped")
    }
}
