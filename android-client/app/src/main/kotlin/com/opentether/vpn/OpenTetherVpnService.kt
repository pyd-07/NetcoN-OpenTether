package com.opentether.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.opentether.Constants
import com.opentether.MainActivity
import com.opentether.StatsHolder
import com.opentether.data.AppPreferences
import com.opentether.data.TunnelTransport
import com.opentether.logging.AppLogger
import com.opentether.runtime.TunnelRuntimeHolder
import java.net.InetAddress

private const val TAG = "OT/VpnService"

const val ACTION_START = "com.opentether.action.START"
const val ACTION_STOP = "com.opentether.action.STOP"

class OpenTetherVpnService : VpnService() {

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelSession: TunnelSession? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLogger.i(TAG, "STOP received")
                AppPreferences.setVpnRequested(this, false)
                stopVpn()
                stopSelfResult(startId)
            }
            ACTION_START -> {
                AppLogger.i(TAG, "START received")
                AppPreferences.setVpnRequested(this, true)
                startVpn(startId)
            }
            else -> {
                val shouldRestore = AppPreferences.current(this).vpnRequested
                AppLogger.i(TAG, "service recreated by system; restore=$shouldRestore")
                if (shouldRestore) {
                    startVpn(startId)
                } else {
                    stopSelfResult(startId)
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        AppLogger.i(TAG, "revoked by system")
        AppPreferences.setVpnRequested(this, false)
        stopVpn()
        super.onRevoke()
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not interpret the launcher task being swiped away as a request to
        // stop the VPN. The foreground service owns the tunnel lifecycle.
        AppLogger.i(TAG, "task removed; keeping VPN service alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun startVpn(startId: Int) {
        if (vpnInterface != null || tunnelSession != null) {
            AppLogger.w(TAG, "VPN already active — ignoring duplicate START")
            return
        }

        if (!promoteToForeground()) {
            val transport = AppPreferences.current(this).preferredTransport
            AppLogger.e(TAG, "foreground startup failed")
            TunnelRuntimeHolder.onError(transport, "Foreground service startup failed")
            stopSelfResult(startId)
            return
        }

        stopping = false
        val transport = AppPreferences.current(this).preferredTransport
        TunnelRuntimeHolder.onServiceStarting(transport)

        val iface = buildVpnInterface() ?: run {
            AppLogger.e(TAG, "establish() returned null")
            TunnelRuntimeHolder.onError(transport, "Unable to create VPN interface")
            stopSelfResult(startId)
            return
        }

        val session = TunnelSession(this, iface, transport)
        try {
            vpnInterface = iface
            tunnelSession = session
            session.start()
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to start tunnel session: ${e.message}")
            session.stop()
            try {
                iface.close()
            } catch (_: Exception) {
                // Best-effort cleanup after failed startup.
            }
            tunnelSession = null
            vpnInterface = null
            StatsHolder.setRunning(false)
            TunnelRuntimeHolder.onError(transport, "Unable to start tunnel session: ${e.message}")
            stopSelfResult(startId)
            return
        }

        AppLogger.i(TAG, "VPN running — connecting via ${transport.label}")
        StatsHolder.setRunning(true)
        TunnelRuntimeHolder.onTunEstablished()
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
        if (stopping && vpnInterface == null && tunnelSession == null) return
        stopping = true

        AppLogger.i(TAG, "stopping")
        StatsHolder.setRunning(false)
        TunnelRuntimeHolder.onServiceStopping()

        tunnelSession?.stop()
        tunnelSession = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            AppLogger.w(TAG, "error closing TUN: ${e.message}")
        }
        vpnInterface = null

        TunnelRuntimeHolder.onStopped()
        AppLogger.i(TAG, "stopped")
    }

    private fun promoteToForeground(): Boolean {
        return try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        Constants.NOTIFICATION_CHANNEL_ID,
                        "OpenTether VPN",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Active while USB tethering is running" },
                )
            }

            val openApp = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )

            val stopIntent = PendingIntent.getService(
                this,
                1,
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    Constants.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "startForeground failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
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
