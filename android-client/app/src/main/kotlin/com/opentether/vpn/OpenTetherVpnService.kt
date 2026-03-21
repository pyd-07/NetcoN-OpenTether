package com.opentether.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.opentether.Constants
import com.opentether.MainActivity
import com.opentether.StatsHolder
import com.opentether.data.AppPreferences
import com.opentether.data.TunnelTransport
import com.opentether.logging.AppLogger
import com.opentether.runtime.TunnelRuntimeHolder
import com.opentether.tunnel.AoaTunnelClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

import com.opentether.tunnel.UsbTunnelClient

private const val TAG = "OT/VpnService"

const val ACTION_START = "com.opentether.action.START"
const val ACTION_STOP  = "com.opentether.action.STOP"

class OpenTetherVpnService : VpnService() {

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    private val outboundChannel = Channel<ByteArray>(Constants.OUTBOUND_CHANNEL_CAPACITY)
    private val inboundChannel  = Channel<ByteArray>(Constants.INBOUND_CHANNEL_CAPACITY)

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                AppLogger.i(TAG, "START received")
                startForegroundNotification()
                startVpn()
            }
            ACTION_STOP -> {
                AppLogger.i(TAG, "STOP received")
                stopVpn()
                stopSelf()
            }
            else -> {
                AppLogger.i(TAG, "restarted by system")
                startForegroundNotification()
                startVpn()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        AppLogger.i(TAG, "revoked by system")
        super.onRevoke()
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    // ── VPN start / stop ───────────────────────────────────────────────────

    private fun startVpn() {
        val transport = AppPreferences.current(this).preferredTransport
        TunnelRuntimeHolder.onServiceStarting(transport)

        val iface = buildVpnInterface() ?: run {
            AppLogger.e(TAG, "establish() returned null — aborting")
            TunnelRuntimeHolder.onError(transport, "Unable to create VPN interface")
            stopSelf()
            return
        }
        vpnInterface = iface
        AppLogger.i(TAG, "TUN interface established (fd=${iface.fd})")

        StatsHolder.setRunning(true)
        TunnelRuntimeHolder.onTunEstablished()

        val fd = iface.fileDescriptor
        TunReader(fd, outboundChannel).start(serviceScope)
        TunWriter(fd, inboundChannel).start(serviceScope)
        when (transport) {
            TunnelTransport.ADB -> UsbTunnelClient(outboundChannel, inboundChannel, this).start(serviceScope)
            TunnelTransport.AOA -> AoaTunnelClient(outboundChannel, inboundChannel, this).start(serviceScope)
        }

        // ── Stats ticker ──────────────────────────────────────────────────
        // Runs every second regardless of relay connection state.
        // Samples StatsHolder's atomic byte counters and publishes VpnStats.
        serviceScope.launch(Dispatchers.IO) {
            AppLogger.i(TAG, "stats ticker started")
            while (isActive) {
                delay(1_000L)
                StatsHolder.tick()
            }
            AppLogger.i(TAG, "stats ticker stopped")
        }

        AppLogger.i(TAG, "VPN running — connecting via ${transport.label}")
    }

    private fun buildVpnInterface(): ParcelFileDescriptor? {
        val dnsServer = AppPreferences.current(this).dnsServer.ifBlank { Constants.VPN_DNS_SERVER }
        return try {
            Builder()
                .addAddress(Constants.VPN_CLIENT_IP, Constants.VPN_PREFIX)
                .addAddress("fdcc::1", 64)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(normalizeDnsServer(dnsServer))
                .setMtu(Constants.VPN_MTU)
                .setSession(Constants.VPN_SESSION_NAME)
                .establish()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Builder.establish() threw: ${e.message}")
            null
        }
    }

    private fun stopVpn() {
        AppLogger.i(TAG, "stopping")
        StatsHolder.setRunning(false)
        TunnelRuntimeHolder.onServiceStopping()
        serviceScope.cancel("VPN stopped")
        outboundChannel.close()
        inboundChannel.close()
        try { vpnInterface?.close() } catch (e: Exception) {
            AppLogger.w(TAG, "error closing TUN: ${e.message}")
        }
        vpnInterface = null
        TunnelRuntimeHolder.onStopped()
        AppLogger.i(TAG, "stopped")
    }

    // ── Foreground notification ────────────────────────────────────────────

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)

        if (nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_ID,
                    "OpenTether VPN",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Active while USB tethering is running" }
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OpenTetherVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat
            .Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NetcoN OpenTether active")
            .setContentText("Routing traffic through workstation")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(Constants.NOTIFICATION_ID, notification)
    }

    private fun normalizeDnsServer(value: String): String {
        return try {
            InetAddress.getByName(value.trim()).hostAddress ?: Constants.VPN_DNS_SERVER
        } catch (_: Exception) {
            AppLogger.w(TAG, "Invalid DNS value '$value', falling back to ${Constants.VPN_DNS_SERVER}")
            Constants.VPN_DNS_SERVER
        }
    }
}
