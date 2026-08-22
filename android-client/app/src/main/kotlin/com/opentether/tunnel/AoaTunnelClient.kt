package com.opentether.tunnel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.opentether.Constants
import com.opentether.StatsHolder
import com.opentether.data.TunnelTransport
import com.opentether.logging.AppLogger
import com.opentether.model.OtpFrame
import com.opentether.runtime.TunnelRuntimeHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

private const val TAG = "OT/AoaTunnelClient"
private const val ACTION_USB_PERMISSION = "com.opentether.action.USB_PERMISSION"

class AoaTunnelClient(
    private val outbound: Channel<ByteArray>,
    private val inbound: Channel<ByteArray>,
    private val vpnService: VpnService,
) {
    private val connIdCounter = AtomicInteger(1)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        AppLogger.i(TAG, "started — waiting for USB accessory")
        TunnelRuntimeHolder.onTransportWaiting(
            transport = TunnelTransport.AOA,
            detail = "Plug in the cable, run the relay in AOA mode, and accept the phone prompt",
        )

        while (isActive) {
            val pfd = awaitAccessory(vpnService) ?: return@launch

            AppLogger.i(TAG, "USB accessory open — starting session")
            TunnelRuntimeHolder.onTransportConnecting(
                transport = TunnelTransport.AOA,
                detail = "Accessory detected, opening bulk endpoints",
            )
            try {
                val input = DataInputStream(
                    BufferedInputStream(FileInputStream(pfd.fileDescriptor), 65536),
                )
                val output = FileOutputStream(pfd.fileDescriptor)
                TunnelRuntimeHolder.onTransportConnected(
                    transport = TunnelTransport.AOA,
                    detail = "Accessory session established",
                )
                runSession(pfd, input, output)
            } catch (e: IOException) {
                if (!isActive) return@launch
                AppLogger.w(TAG, "session IO error: ${e.message}")
            } catch (e: Exception) {
                if (!isActive) return@launch
                AppLogger.e(TAG, "session unexpected error: ${e.message}")
                TunnelRuntimeHolder.onError(
                    TunnelTransport.AOA,
                    "Accessory session failed: ${e.message}",
                )
            } finally {
                try { pfd.close() } catch (_: Exception) {}
                StatsHolder.rttMs.set(0)
            }

            if (!isActive) return@launch
            AppLogger.i(TAG, "accessory disconnected — waiting for reconnect")
            TunnelRuntimeHolder.onTransportDisconnected(
                transport = TunnelTransport.AOA,
                detail = "Accessory disconnected, waiting for reconnect",
            )
            delay(Constants.RECONNECT_DELAY_MS)
        }
    }

    private suspend fun awaitAccessory(context: Context): ParcelFileDescriptor? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        while (coroutineContext.isActive) {
            val accessory = usbManager.accessoryList?.firstOrNull()
            if (accessory == null) {
                TunnelRuntimeHolder.onTransportWaiting(
                    transport = TunnelTransport.AOA,
                    detail = "No USB accessory detected. Reconnect the cable after starting the relay in AOA mode.",
                )
            } else {
                val pfd = requestPermissionAndOpen(context, usbManager, accessory)
                if (pfd != null) return pfd
            }
            delay(1_000L)
        }
        return null
    }

    private suspend fun requestPermissionAndOpen(
        context: Context,
        usbManager: UsbManager,
        accessory: UsbAccessory,
    ): ParcelFileDescriptor? {
        AppLogger.i(TAG, "accessory found: ${accessory.manufacturer} / ${accessory.model}")

        val granted = requestUsbAccessoryPermission(context, usbManager, accessory)
        if (!granted) {
            AppLogger.e(TAG, "USB permission denied by user")
            TunnelRuntimeHolder.onError(TunnelTransport.AOA, "USB accessory permission denied")
            return null
        }

        return try {
            usbManager.openAccessory(accessory).also {
                if (it == null) {
                    AppLogger.e(TAG, "openAccessory returned null")
                    TunnelRuntimeHolder.onError(
                        TunnelTransport.AOA,
                        "Unable to open USB accessory",
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "openAccessory threw: ${e.message}")
            TunnelRuntimeHolder.onError(
                TunnelTransport.AOA,
                "Unable to open USB accessory: ${e.message}",
            )
            null
        }
    }

    private suspend fun requestUsbAccessoryPermission(
        context: Context,
        usbManager: UsbManager,
        accessory: UsbAccessory,
    ): Boolean {
        if (usbManager.hasPermission(accessory)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    try { receiverContext.unregisterReceiver(this) } catch (_: Exception) {}
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false,
                    )
                    AppLogger.i(TAG, "USB accessory permission result: granted=$granted")
                    if (continuation.isActive) continuation.resume(granted)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )

            try {
                usbManager.requestPermission(accessory, permissionIntent)
            } catch (e: Exception) {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (continuation.isActive) continuation.resume(false)
                AppLogger.e(TAG, "requestPermission threw: ${e.message}")
            }

            continuation.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun runSession(
        pfd: ParcelFileDescriptor,
        input: DataInputStream,
        output: FileOutputStream,
    ) {
        val decoder = PacketDecoder(input)
        val writeMu = Any()
        val pingTimestamp = AtomicLong(0L)

        val sessionJob = Job()
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob)

        val sendJob = sessionScope.launch { sendLoop(output, writeMu) }
        val recvJob = sessionScope.launch { receiveLoop(decoder, pingTimestamp) }
        val pingJob = sessionScope.launch { pingLoop(output, writeMu, pingTimestamp) }

        try {
            select<Unit> {
                sendJob.onJoin { }
                recvJob.onJoin { }
            }
        } finally {
            withContext(NonCancellable) {
                sessionJob.cancel()
                try { pfd.close() } catch (_: Exception) {}
                sendJob.join()
                recvJob.join()
                pingJob.join()
            }
        }
    }

    private suspend fun sendLoop(output: FileOutputStream, writeMu: Any) {
        try {
            for (rawPacket in outbound) {
                val connId = connIdCounter.getAndIncrement()
                val frame = PacketEncoder.encode(connId, rawPacket, Constants.MSG_DATA)
                synchronized(writeMu) { output.write(frame) }
                StatsHolder.bytesUpSec.addAndGet(rawPacket.size.toLong())
                StatsHolder.totalUp.addAndGet(rawPacket.size.toLong())
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "sendLoop: ${e.message}")
        }
    }

    private suspend fun pingLoop(
        output: FileOutputStream,
        writeMu: Any,
        pingTimestamp: AtomicLong,
    ) {
        try {
            while (true) {
                delay(PING_INTERVAL_MS)
                pingTimestamp.set(System.currentTimeMillis())
                val frame = PacketEncoder.encodeControl(msgType = Constants.MSG_PING)
                synchronized(writeMu) { output.write(frame) }
            }
        } catch (_: IOException) {
            AppLogger.w(TAG, "pingLoop: stream gone")
        }
    }

    private suspend fun receiveLoop(decoder: PacketDecoder, pingTimestamp: AtomicLong) {
        try {
            while (true) {
                val frame: OtpFrame = decoder.readFrame()
                when (frame.msgType) {
                    Constants.MSG_DATA -> {
                        val payload = frame.payload ?: continue
                        StatsHolder.bytesDownSec.addAndGet(payload.size.toLong())
                        StatsHolder.totalDown.addAndGet(payload.size.toLong())
                        inbound.send(payload)
                    }
                    Constants.MSG_PONG -> {
                        val sentAt = pingTimestamp.get()
                        if (sentAt > 0L) {
                            val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(0)
                            StatsHolder.rttMs.set(rtt)
                        }
                    }
                    Constants.MSG_CLOSE -> AppLogger.d(TAG, "← accessory CLOSE conn_id=${frame.connId}")
                    Constants.MSG_ERROR -> {
                        val msg = frame.payload?.let { String(it, Charsets.UTF_8) } ?: "(none)"
                        AppLogger.e(TAG, "← accessory ERROR: $msg")
                        TunnelRuntimeHolder.onError(TunnelTransport.AOA, "Accessory error: $msg")
                    }
                    else -> AppLogger.w(TAG, "← unknown type 0x${frame.msgType.toString(16)}")
                }
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "receiveLoop: ${e.message}")
        }
    }

    companion object {
        private const val PING_INTERVAL_MS = 2_000L
    }
}
