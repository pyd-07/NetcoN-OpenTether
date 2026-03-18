package com.opentether.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.opentether.Constants
import com.opentether.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

import com.opentether.tunnel.UsbTunnelClient

private const val TAG = "OT/VpnService"

const val ACTION_START = "com.opentether.action.START"
const val ACTION_STOP  = "com.opentether.action.STOP"

class OpenTetherVpnService : VpnService() {

    // SupervisorJob: a crash in one child coroutine doesn't cancel the others.
    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null

    private val outboundChannel = Channel<ByteArray>(Constants.OUTBOUND_CHANNEL_CAPACITY)
    private val inboundChannel  = Channel<ByteArray>(Constants.INBOUND_CHANNEL_CAPACITY)

    // ── Service lifecycle ──────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "START received")
                startForegroundNotification()
                startVpn()
            }
            ACTION_STOP -> {
                Log.i(TAG, "STOP received")
                stopVpn()
                stopSelf()
            }
            else -> {
                // Restarted by system after low-memory kill (START_STICKY).
                Log.i(TAG, "restarted by system")
                startForegroundNotification()
                startVpn()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // User disabled VPN from Settings, or another VPN took over.
        Log.i(TAG, "revoked by system")
        super.onRevoke()
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    // ── VPN start / stop ───────────────────────────────────────────────────

    private fun startVpn() {
        val iface = buildVpnInterface() ?: run {
            Log.e(TAG, "establish() returned null — aborting")
            stopSelf()
            return
        }
        vpnInterface = iface
        Log.i(TAG, "TUN interface established (fd=${iface.fd})")

        val fd = iface.fileDescriptor
        TunReader(fd, outboundChannel).start(serviceScope)
        TunWriter(fd, inboundChannel).start(serviceScope)
        UsbTunnelClient(outboundChannel, inboundChannel, this).start(serviceScope)

        Log.i(TAG, "VPN running — connecting to relay")
    }

    private fun buildVpnInterface(): ParcelFileDescriptor? {
        return try {
            Builder()
                .addAddress(Constants.VPN_CLIENT_IP, Constants.VPN_PREFIX)
                .addAddress("fdcc::1", 64)
                .addRoute("0.0.0.0", 0)        // capture all IPv4
                .addRoute("::", 0)              // capture all IPv6
                .addDnsServer(Constants.VPN_DNS_SERVER)
                .setMtu(Constants.VPN_MTU)
                .setSession(Constants.VPN_SESSION_NAME)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Builder.establish() threw: ${e.message}")
            null
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "stopping")

        // Cancel all coroutines — loops exit on next isActive check.
        serviceScope.cancel("VPN stopped")

        // Close channels — unblocks any suspended send()/receive().
        outboundChannel.close()
        inboundChannel.close()

        // Close TUN fd — causes FileInputStream.read() to throw immediately.
        try { vpnInterface?.close() } catch (e: Exception) {
            Log.w(TAG, "error closing TUN: ${e.message}")
        }
        vpnInterface = null

        Log.i(TAG, "stopped")
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
            .setContentTitle("OpenTether active")
            .setContentText("Routing traffic through PC")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(Constants.NOTIFICATION_ID, notification)
    }
}
