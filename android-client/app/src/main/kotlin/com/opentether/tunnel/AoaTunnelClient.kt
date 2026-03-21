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
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val TAG = "OT/AoaTunnelClient"
private const val ACTION_USB_PERMISSION = "com.opentether.USB_PERMISSION"

/**
 * Android Open Accessory (AOA) tunnel — the cable-only alternative to
 * [UsbTunnelClient]. Identical session structure (send / receive / ping /
 * flush coroutines) but uses a USB bulk pipe instead of an ADB TCP tunnel.
 *
 * Permission handling
 * ───────────────────
 * Android only auto-grants USB accessory permission when the app is launched
 * via the USB_ACCESSORY_ATTACHED intent filter. When the user manually taps
 * "Start" with the cable already plugged in, [UsbManager.openAccessory] would
 * throw. [requestPermissionAndOpen] checks [UsbManager.hasPermission] first
 * and, if absent, shows the system dialog and suspends until the user responds.
 *
 * Session lifecycle — see [UsbTunnelClient] for full commentary.
 */
class AoaTunnelClient(
    private val outbound:   Channel<ByteArray>,
    private val inbound:    Channel<ByteArray>,
    private val vpnService: VpnService,
) {
    private val connIdCounter = AtomicInteger(1)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        AppLogger.i(TAG, "started — waiting for USB accessory")
        TunnelRuntimeHolder.onTransportWaiting(
            transport = TunnelTransport.AOA,
            detail    = "Plug in the cable, run the relay in AOA mode, and accept the phone prompt",
        )

        while (isActive) {
            val pfd = awaitAccessory(vpnService) ?: return@launch

            AppLogger.i(TAG, "USB accessory open — starting session")
            TunnelRuntimeHolder.onTransportConnecting(
                transport = TunnelTransport.AOA,
                detail    = "Accessory detected, opening bulk endpoints",
            )
            try {
                val input  = DataInputStream(FileInputStream(pfd.fileDescriptor))
                val output = FileOutputStream(pfd.fileDescriptor)
                TunnelRuntimeHolder.onTransportConnected(
                    transport = TunnelTransport.AOA,
                    detail    = "Accessory session established",
                )
                runSession(pfd, input, output)
            } catch (e: IOException) {
                if (!isActive) return@launch
                AppLogger.w(TAG, "session IO error: ${e.message}")
            } catch (e: Exception) {
                if (!isActive) return@launch
                AppLogger.e(TAG, "session unexpected error: ${e.message}")
                TunnelRuntimeHolder.onError(TunnelTransport.AOA, "Accessory session failed: ${e.message}")
            } finally {
                try { pfd.close() } catch (_: Exception) {}  // no-op if runSession already closed it
                StatsHolder.rttMs.set(0)
            }

            if (!isActive) return@launch
            AppLogger.i(TAG, "accessory disconnected — waiting for reconnect")
            TunnelRuntimeHolder.onTransportDisconnected(
                transport = TunnelTransport.AOA,
                detail    = "Accessory disconnected, waiting for reconnect",
            )
            delay(Constants.RECONNECT_DELAY_MS)
        }
    }

    // ─── Accessory detection ──────────────────────────────────────────────

    private suspend fun awaitAccessory(ctx: Context): ParcelFileDescriptor? {
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

        while (true) {
            val accessory = usbManager.accessoryList?.firstOrNull()
            if (accessory == null) {
                TunnelRuntimeHolder.onTransportWaiting(
                    transport = TunnelTransport.AOA,
                    detail    = "No USB accessory detected. Reconnect the cable after starting the relay in AOA mode.",
                )
            } else {
                val pfd = requestPermissionAndOpen(ctx, usbManager, accessory)
                if (pfd != null) return pfd
            }
            delay(1_000L)
        }
    }

    /**
     * Ensures USB accessory permission is held, then calls [UsbManager.openAccessory].
     *
     * - Permission already granted → opens immediately, no dialog.
     * - Permission absent → shows system dialog, suspends until user responds.
     *   Returns null if the user denies or the coroutine is cancelled.
     */
    private suspend fun requestPermissionAndOpen(
        ctx:        Context,
        usbManager: UsbManager,
        accessory:  UsbAccessory,
    ): ParcelFileDescriptor? {
        AppLogger.i(TAG, "accessory found: ${accessory.manufacturer} / ${accessory.model}")

        if (usbManager.hasPermission(accessory)) {
            return tryOpen(usbManager, accessory)
        }

        AppLogger.i(TAG, "requesting USB accessory permission")
        TunnelRuntimeHolder.onTransportWaiting(
            transport = TunnelTransport.AOA,
            detail    = "Accept the USB accessory permission dialog on your phone.",
        )

        val granted = suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    val allowed = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    AppLogger.i(TAG, "USB permission result: granted=$allowed")
                    if (cont.isActive) cont.resume(allowed)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation {
                try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(accessory, pi)
        }

        if (!granted) {
            AppLogger.e(TAG, "USB permission denied by user")
            return null
        }
        return tryOpen(usbManager, accessory)
    }

    private fun tryOpen(usbManager: UsbManager, accessory: UsbAccessory): ParcelFileDescriptor? =
        try {
            usbManager.openAccessory(accessory).also {
                if (it == null) AppLogger.e(TAG, "openAccessory returned null (permission denied?)")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "openAccessory threw: ${e.message}")
            null
        }

    // ─── Session ──────────────────────────────────────────────────────────

    /**
     * Runs send, receive, ping, and flush coroutines for one accessory connection.
     * Ends (and closes [pfd]) as soon as either sendJob or recvJob exits.
     */
    private suspend fun runSession(
        pfd:    ParcelFileDescriptor,
        input:  DataInputStream,
        output: FileOutputStream,
    ) {
        val decoder       = PacketDecoder(input)
        val bos           = BufferedOutputStream(output, SEND_BUF_SIZE)
        val pingTimestamp = AtomicLong(0L)

        val sessionJob   = Job()
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob)

        val sendJob  = sessionScope.launch { sendLoop(bos) }
        val recvJob  = sessionScope.launch { receiveLoop(decoder, pingTimestamp) }
        val pingJob  = sessionScope.launch { pingLoop(bos, pingTimestamp) }
        val flushJob = sessionScope.launch { flushLoop(bos) }

        try {
            select<Unit> {
                sendJob.onJoin { }
                recvJob.onJoin { }
            }
        } finally {
            withContext(NonCancellable) {
                sessionJob.cancel()
                try { pfd.close() } catch (_: Exception) {}  // unblocks readFully
                sendJob.join()
                recvJob.join()
                pingJob.join()
                flushJob.join()
            }
        }
    }

    // ─── Send loop ────────────────────────────────────────────────────────

    private suspend fun sendLoop(bos: BufferedOutputStream) {
        AppLogger.d(TAG, "sendLoop started")
        try {
            for (rawPacket in outbound) {
                val connId = connIdCounter.getAndIncrement()
                val frame  = PacketEncoder.encode(connId, rawPacket, Constants.MSG_DATA)

                synchronized(bos) {
                    bos.write(frame)
                    if (rawPacket.size >= FLUSH_THRESHOLD) bos.flush()
                }

                StatsHolder.bytesUpSec.addAndGet(rawPacket.size.toLong())
                StatsHolder.totalUp.addAndGet(rawPacket.size.toLong())
                AppLogger.d(TAG, "→ accessory ${rawPacket.size}B  conn_id=$connId")
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "sendLoop: ${e.message}")
        } finally {
            AppLogger.d(TAG, "sendLoop stopped")
        }
    }

    // ─── Flush loop ───────────────────────────────────────────────────────

    private suspend fun flushLoop(bos: BufferedOutputStream) {
        try {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                synchronized(bos) { bos.flush() }
            }
        } catch (_: IOException) {
            // Stream gone; sendLoop will detect the same error and exit.
        }
    }

    // ─── Ping loop ────────────────────────────────────────────────────────

    private suspend fun pingLoop(bos: BufferedOutputStream, pingTimestamp: AtomicLong) {
        AppLogger.d(TAG, "pingLoop started")
        try {
            while (true) {
                delay(PING_INTERVAL_MS)
                pingTimestamp.set(System.currentTimeMillis())
                val frame = PacketEncoder.encodeControl(msgType = Constants.MSG_PING)
                synchronized(bos) {
                    bos.write(frame)
                    bos.flush()
                }
                AppLogger.d(TAG, "→ accessory PING")
            }
        } catch (_: IOException) {
            AppLogger.w(TAG, "pingLoop: stream gone")
        } finally {
            AppLogger.d(TAG, "pingLoop stopped")
        }
    }

    // ─── Receive loop ─────────────────────────────────────────────────────

    private suspend fun receiveLoop(decoder: PacketDecoder, pingTimestamp: AtomicLong) {
        AppLogger.d(TAG, "receiveLoop started")
        try {
            while (true) {
                val frame: OtpFrame = decoder.readFrame()
                when (frame.msgType) {
                    Constants.MSG_DATA -> {
                        val payload = frame.payload ?: continue
                        AppLogger.d(TAG, "← accessory ${payload.size}B  conn_id=${frame.connId}")
                        StatsHolder.bytesDownSec.addAndGet(payload.size.toLong())
                        StatsHolder.totalDown.addAndGet(payload.size.toLong())
                        inbound.send(payload)
                    }
                    Constants.MSG_PONG -> {
                        val sentAt = pingTimestamp.get()
                        if (sentAt > 0L) {
                            val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(0)
                            StatsHolder.rttMs.set(rtt)
                            AppLogger.d(TAG, "← accessory PONG  rtt=${rtt}ms")
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
        } finally {
            AppLogger.d(TAG, "receiveLoop stopped")
        }
    }

    companion object {
        private const val FLUSH_THRESHOLD   = 512
        private const val FLUSH_INTERVAL_MS = 2L
        private const val SEND_BUF_SIZE     = 32_768
        private const val PING_INTERVAL_MS  = 2_000L
    }
}