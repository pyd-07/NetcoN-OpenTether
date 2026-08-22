package com.opentether.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
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
import java.net.InetAddress

private const val TAG = "OT/VpnService"

const val ACTION_START = "com.opentether.action.START"
const val ACTION_STOP = "com.opentether.action.STOP"

class OpenTetherVpnService : VpnService() {

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceScope: CoroutineScope? = null
    private var serviceJob: Job? = null
    private var runtimeJob: Job? = null
    private var outboundChannel: Channel<ByteArray>? = null
    private var inboundChannel: Channel<ByteArray>? = null
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppLogger.i(TAG, "STOP received")
                stopVpn()
                stopSelfResult(startId)
            }
            ACTION_START -> {
                AppLogger.i(TAG, "START received")
                if (!promoteToForeground()) {
                    AppLogger.e(TAG, "foreground startup failed")
                    TunnelRuntimeHolder.onError(
                        AppPreferences.current(this).preferredTransport,
                        "Foreground service startup failed",
                    )
                    stopSelfResult(startId)
                } else {
                    startVpn()
                }
            }
            else -> {
                AppLogger.i(TAG, "service recreated by system")
                if (!promoteToForeground()) {
                    AppLogger.e(TAG, "foreground startup failed after service recreation")
                    TunnelRuntimeHolder.onError(
                        AppPreferences.current(this).preferredTransport,
                        "Foreground service startup failed after service recreation",
                    )
                    stopSelfResult(startId)
                } else {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        AppLogger.i(TAG, "revoked by system")
        stopVpn()
        super.onRevoke()
        stopSelf()
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "onDestroy")
        stopVpn()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun startVpn() {
        if (vpnInterface != null || serviceScope != null) {
            AppLogger.w(TAG, "VPN already active — ignoring duplicate START")
            return
        }

        stopping = false
        val transport = AppPreferences.current(this).preferredTransport
        TunnelRuntimeHolder.onServiceStarting(transport)

        val iface = buildVpnInterface() ?: run {
            AppLogger.e(TAG, "establish() returned null")
            TunnelRuntimeHolder.onError(transport, "Unable to create VPN interface")
            stopSelf()
            return
        }

        vpnInterface = iface
        outboundChannel = Channel(Constants.OUTBOUND_CHANNEL_CAPACITY)
        inboundChannel = Channel(Constants.INBOUND_CHANNEL_CAPACITY)
        serviceJob = SupervisorJob()
        serviceScope = CoroutineScope(Dispatchers.IO + serviceJob!!)

        AppLogger.i(TAG, "TUN interface established (fd=${iface.fd})")
        StatsHolder.setRunning(true)
        TunnelRuntimeHolder.onTunEstablished()

        val scope = serviceScope!!
        val outbound = outboundChannel!!
        val inbound = inboundChannel!!

        TunReader(iface.fileDescriptor, outbound).start(scope)
        TunWriter(iface.fileDescriptor, inbound).start(scope)
        when (transport) {
            TunnelTransport.ADB -> UsbTunnelClient(outbound, inbound, this).start(scope)
            TunnelTransport.AOA -> AoaTunnelClient(outbound, inbound, this).start(scope)
        }

        runtimeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_000L)
                StatsHolder.tick()
            }
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
        if (stopping && vpnInterface == null && serviceScope == null) return
        stopping = true

        AppLogger.i(TAG, "stopping")
        StatsHolder.setRunning(false)
        TunnelRuntimeHolder.onServiceStopping()

        runtimeJob?.cancel()
        runtimeJob = null
        serviceScope?.cancel("VPN stopped")
        serviceScope = null
        serviceJob = null

        outboundChannel?.close()
        outboundChannel = null
        inboundChannel?.close()
        inboundChannel = null

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
                startForeground(
                    Constants.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Constants.NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "startForeground failed: ${e.message}")
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
