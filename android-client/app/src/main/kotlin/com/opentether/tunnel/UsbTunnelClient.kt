package com.opentether.tunnel

import android.net.VpnService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "OT/UsbTunnelClient"

/**
 * Manages the TCP socket connection to the relay over the ADB reverse tunnel.
 *
 * Session lifecycle
 * ─────────────────
 * [runSession] launches four coroutines in an isolated [Job]:
 *   sendJob   — drains the outbound channel, writes OTP DATA frames
 *   recvJob   — reads OTP frames from the relay; measures RTT on MSG_PONG
 *   pingJob   — sends MSG_PING every [PING_INTERVAL_MS] ms
 *   flushJob  — flushes the shared [BufferedOutputStream] every [FLUSH_INTERVAL_MS] ms
 *
 * The session ends as soon as *either* sendJob or recvJob exits (connection
 * lost or protocol error). The remaining coroutines are cancelled, the fd is
 * closed, and the outer loop reconnects.
 *
 * UDP / DNS optimisation
 * ──────────────────────
 * All four coroutines share one [BufferedOutputStream] (32 KB). Writes are
 * guarded by `synchronized(bos)`. Large packets (≥ [FLUSH_THRESHOLD] bytes)
 * are flushed immediately; smaller packets are batched and drained by
 * flushJob, bounding added latency to [FLUSH_INTERVAL_MS] ms.
 *
 * Live RTT measurement
 * ────────────────────
 * pingJob records a timestamp immediately before sending each MSG_PING.
 * When recvJob sees the corresponding MSG_PONG it computes
 * `now − pingTimestamp` and writes the result to [StatsHolder.rttMs].
 */
class UsbTunnelClient(
    private val outbound:   Channel<ByteArray>,
    private val inbound:    Channel<ByteArray>,
    private val vpnService: VpnService,
) {
    private val connIdCounter = AtomicInteger(1)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        AppLogger.i(TAG, "started — will connect to ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}")
        TunnelRuntimeHolder.onTransportWaiting(
            transport = TunnelTransport.ADB,
            detail    = "Waiting for relay on ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
        )

        while (isActive) {
            AppLogger.i(TAG, "connecting to relay...")
            TunnelRuntimeHolder.onTransportConnecting(
                transport = TunnelTransport.ADB,
                detail    = "Dialing ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
            )

            var fd: FileDescriptor? = null
            try {
                fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)

                val intFd = intFd(fd)
                if (intFd == -1) {
                    AppLogger.e(TAG, "could not read native fd — will retry")
                    delay(Constants.RECONNECT_DELAY_MS)
                    continue
                }
                if (!vpnService.protect(intFd)) {
                    AppLogger.e(TAG, "protect(int) returned false — will retry")
                    delay(Constants.RECONNECT_DELAY_MS)
                    continue
                }

                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1)
                Os.connect(fd, InetAddress.getByName(Constants.RELAY_HOST), Constants.RELAY_PORT)
                AppLogger.i(TAG, "connected to relay")
                TunnelRuntimeHolder.onTransportConnected(
                    transport = TunnelTransport.ADB,
                    detail    = "Relay session established on ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
                )

                runSession(fd, DataInputStream(FileInputStream(fd)), FileOutputStream(fd))

            } catch (e: ErrnoException) {
                if (!isActive) return@launch
                AppLogger.w(TAG, "OS error: ${e.message} (errno=${e.errno})")
            } catch (e: IOException) {
                if (!isActive) return@launch
                AppLogger.w(TAG, "IO error: ${e.message}")
            } catch (e: Exception) {
                if (!isActive) return@launch
                AppLogger.e(TAG, "unexpected: ${e.message}")
                TunnelRuntimeHolder.onError(TunnelTransport.ADB, "Unexpected tunnel error: ${e.message}")
            } finally {
                fd?.let { closeFd(it) }  // no-op if runSession already closed it
                StatsHolder.rttMs.set(0)
            }

            if (!isActive) return@launch
            AppLogger.i(TAG, "disconnected — retrying in ${Constants.RECONNECT_DELAY_MS}ms")
            TunnelRuntimeHolder.onTransportDisconnected(
                transport = TunnelTransport.ADB,
                detail    = "Relay disconnected, retrying in ${Constants.RECONNECT_DELAY_MS} ms",
            )
            delay(Constants.RECONNECT_DELAY_MS)
        }
    }

    // ─── Session ──────────────────────────────────────────────────────────

    /**
     * Runs send, receive, ping, and flush coroutines for one relay connection.
     *
     * Blocks until either the send or receive path exits, then cancels all
     * remaining coroutines, closes [fd] to unblock any pending reads, and
     * returns so the caller can reconnect.
     *
     * The isolated [sessionJob] ensures cancellation does not escape to the
     * parent service scope.
     */
    private suspend fun runSession(
        fd:     FileDescriptor,
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
            // Suspend until either the outbound send path or inbound receive
            // path exits — either means the connection is gone or broken.
            select<Unit> {
                sendJob.onJoin { }
                recvJob.onJoin { }
            }
        } finally {
            // NonCancellable: the outer coroutine may itself be cancelled
            // (e.g. VPN stopped). We still need to clean up fully.
            withContext(NonCancellable) {
                sessionJob.cancel()  // signal all coroutines to stop
                closeFd(fd)          // unblock any readFully waiting on the stream
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
                    // Large packets flush immediately — TCP bulk / video.
                    // Small packets wait for flushLoop's ticker.
                    if (rawPacket.size >= FLUSH_THRESHOLD) bos.flush()
                }

                StatsHolder.bytesUpSec.addAndGet(rawPacket.size.toLong())
                StatsHolder.totalUp.addAndGet(rawPacket.size.toLong())
                AppLogger.d(TAG, "→ relay ${rawPacket.size}B  conn_id=$connId")
            }
        } catch (e: IOException) {
            AppLogger.w(TAG, "sendLoop: ${e.message}")
        } finally {
            AppLogger.d(TAG, "sendLoop stopped")
        }
    }

    // ─── Flush loop ───────────────────────────────────────────────────────

    /**
     * Flushes [bos] on a regular heartbeat so small frames (DNS, QUIC ACKs)
     * don't sit in the buffer longer than [FLUSH_INTERVAL_MS] ms.
     *
     * Runs as a separate coroutine inside the session scope; cancelled when
     * the session ends.
     */
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

    /**
     * Sends MSG_PING every [PING_INTERVAL_MS] ms and records the send
     * timestamp in [pingTimestamp].
     *
     * The timestamp is set *before* writing so that [receiveLoop] never reads
     * a zero value when the relay responds very quickly (sub-millisecond USB).
     */
    private suspend fun pingLoop(bos: BufferedOutputStream, pingTimestamp: AtomicLong) {
        AppLogger.d(TAG, "pingLoop started")
        try {
            while (true) {
                delay(PING_INTERVAL_MS)
                // Set timestamp before write — if the relay responds before
                // we exit the synchronized block, the RTT is still valid.
                pingTimestamp.set(System.currentTimeMillis())
                val frame = PacketEncoder.encodeControl(msgType = Constants.MSG_PING)
                synchronized(bos) {
                    bos.write(frame)
                    bos.flush()  // flush immediately — RTT accuracy matters
                }
                AppLogger.d(TAG, "→ relay PING")
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
                        AppLogger.d(TAG, "← relay ${payload.size}B  conn_id=${frame.connId}")
                        StatsHolder.bytesDownSec.addAndGet(payload.size.toLong())
                        StatsHolder.totalDown.addAndGet(payload.size.toLong())
                        inbound.send(payload)
                    }
                    Constants.MSG_PONG -> {
                        val sentAt = pingTimestamp.get()
                        if (sentAt > 0L) {
                            val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(0)
                            StatsHolder.rttMs.set(rtt)
                            AppLogger.d(TAG, "← relay PONG  rtt=${rtt}ms")
                        }
                    }
                    Constants.MSG_CLOSE -> AppLogger.d(TAG, "← relay CLOSE conn_id=${frame.connId}")
                    Constants.MSG_ERROR -> {
                        val msg = frame.payload?.let { String(it, Charsets.UTF_8) } ?: "(none)"
                        AppLogger.e(TAG, "← relay ERROR: $msg")
                        TunnelRuntimeHolder.onError(TunnelTransport.ADB, "Relay error: $msg")
                    }
                    else -> AppLogger.w(TAG, "← unknown type 0x${frame.msgType.toString(16)}")
                }
            }
        } catch (e: IOException) {
            // Covers both real IO errors and PacketDecoder's malformed-frame IOException.
            // In both cases the stream is unrecoverable; exit cleanly so the session
            // select fires and triggers a reconnect.
            AppLogger.w(TAG, "receiveLoop: ${e.message}")
        } finally {
            AppLogger.d(TAG, "receiveLoop stopped")
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    private fun intFd(fd: FileDescriptor): Int = try {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        f.getInt(fd)
    } catch (e: Exception) {
        AppLogger.e(TAG, "intFd reflection failed: ${e.message}")
        -1
    }

    private fun closeFd(fd: FileDescriptor) {
        try { Os.close(fd) } catch (_: Exception) {}
    }

    companion object {
        private const val FLUSH_THRESHOLD   = 512    // bytes; above → flush immediately
        private const val FLUSH_INTERVAL_MS = 2L     // ms; max added latency for small frames
        private const val SEND_BUF_SIZE     = 32_768 // bytes; internal BufferedOutputStream buffer
        private const val PING_INTERVAL_MS  = 2_000L // ms; how often to probe RTT
    }
}