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
                // Why BufferedInputStream here?
                // Android's /dev/usb_accessory driver returns exactly one USB bulk
                // transfer per read() syscall. DataInputStream.readFully(12) results
                // in read(12), which dequeues a transfer, returns 12 bytes, and
                // DISCARDS the remainder of that transfer.
                //
                // Wrapping in BufferedInputStream (with a buffer >= max transfer size)
                // ensures the first read() dequeues the entire transfer into memory,
                // allowing subsequent readFully() calls to consume it without loss.
                val input  = DataInputStream(BufferedInputStream(FileInputStream(pfd.fileDescriptor), 65536))
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
     * Runs send, receive, and ping coroutines for one accessory connection.
     * Ends (and closes [pfd]) as soon as either sendJob or recvJob exits.
     *
     * Why no BufferedOutputStream here?
     * Android's /dev/usb_accessory kernel driver returns exactly one USB bulk
     * transfer per read() syscall. If multiple OTP frames are batched together
     * into a single USB transfer by a BufferedOutputStream flush, DataInputStream
     * .readFully() on the relay side would read Frame1's 12-byte header from that
     * transfer, then ask for Frame1's payload bytes — but /dev/usb_accessory
     * gives it Frame2's header bytes instead, producing a garbage payload_length
     * value like 96686829.
     *
     * Each frame (header + payload) is therefore built as one ByteArray and
     * written with a single FileOutputStream.write() call, mapping to exactly
     * one USB bulk OUT transfer. This mirrors the Go relay's DirectWriter /
     * BuildFrame approach.
     */
    private suspend fun runSession(
        pfd:    ParcelFileDescriptor,
        input:  DataInputStream,
        output: FileOutputStream,
    ) {
        val decoder       = PacketDecoder(input)
        val writeMu       = Any()          // guards output across sendJob and pingJob
        val pingTimestamp = AtomicLong(0L)

        val sessionJob   = Job()
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
                try { pfd.close() } catch (_: Exception) {}  // unblocks readFully
                sendJob.join()
                recvJob.join()
                pingJob.join()
            }
        }
    }

    // ─── Send loop ────────────────────────────────────────────────────────

    /**
     * Reads raw IP packets from [outbound], encodes each into a complete OTP
     * frame byte-array, and writes it in a single [output].write() call.
     *
     * One write() call = one USB bulk OUT transfer = one USB bulk IN transfer on
     * the relay side. This is the only correct framing strategy for AOA.
     */
    private suspend fun sendLoop(output: FileOutputStream, writeMu: Any) {
        AppLogger.d(TAG, "sendLoop started")
        try {
            for (rawPacket in outbound) {
                val connId = connIdCounter.getAndIncrement()
                // Build header + payload in one contiguous array, then write
                // with a single write() → single USB bulk OUT transfer.
                val frame = PacketEncoder.encode(connId, rawPacket, Constants.MSG_DATA)
                synchronized(writeMu) { output.write(frame) }

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

    // ─── Ping loop ────────────────────────────────────────────────────────

    private suspend fun pingLoop(output: FileOutputStream, writeMu: Any, pingTimestamp: AtomicLong) {
        AppLogger.d(TAG, "pingLoop started")
        try {
            while (true) {
                delay(PING_INTERVAL_MS)
                pingTimestamp.set(System.currentTimeMillis())
                val frame = PacketEncoder.encodeControl(msgType = Constants.MSG_PING)
                synchronized(writeMu) { output.write(frame) }
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
        private const val PING_INTERVAL_MS = 2_000L
    }
}