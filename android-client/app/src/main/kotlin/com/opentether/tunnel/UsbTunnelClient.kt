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

class UsbTunnelClient(
    private val outbound: Channel<ByteArray>,
    private val inbound: Channel<ByteArray>,
    private val vpnService: VpnService,
) {
    private val connIdCounter = AtomicInteger(1)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        AppLogger.i(TAG, "started — will connect to ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}")
        TunnelRuntimeHolder.onTransportWaiting(
            transport = TunnelTransport.ADB,
            detail = "Waiting for relay on ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
        )

        var reconnectAttempt = 0

        while (isActive) {
            AppLogger.i(TAG, "connecting to relay...")
            TunnelRuntimeHolder.onTransportConnecting(
                transport = TunnelTransport.ADB,
                detail = "Dialing ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
            )

            var fd: FileDescriptor? = null
            var connected = false
            try {
                fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)

                val intFd = intFd(fd)
                if (intFd == -1) {
                    AppLogger.e(TAG, "could not read native fd — will retry")
                    continue
                }
                if (!vpnService.protect(intFd)) {
                    AppLogger.e(TAG, "protect(int) returned false — will retry")
                    continue
                }

                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1)
                Os.connect(fd, InetAddress.getByName(Constants.RELAY_HOST), Constants.RELAY_PORT)
                connected = true
                reconnectAttempt = 0
                AppLogger.i(TAG, "connected to relay")
                TunnelRuntimeHolder.onTransportConnected(
                    transport = TunnelTransport.ADB,
                    detail = "Relay session established on ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}",
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
                fd?.let { closeFd(it) }
                StatsHolder.rttMs.set(0)
            }

            if (!isActive) return@launch

            reconnectAttempt++
            val delayMs = reconnectDelayMs(reconnectAttempt)
            AppLogger.i(TAG, "disconnected — retrying in ${delayMs}ms (attempt=$reconnectAttempt)")
            TunnelRuntimeHolder.onTransportDisconnected(
                transport = TunnelTransport.ADB,
                detail = "Relay disconnected, retrying in $delayMs ms",
            )
            delay(delayMs)
        }
    }

    private suspend fun runSession(
        fd: FileDescriptor,
        input: DataInputStream,
        output: FileOutputStream,
    ) {
        val decoder = PacketDecoder(input)
        val bos = BufferedOutputStream(output, SEND_BUF_SIZE)
        val pingTimestamp = AtomicLong(0L)

        val sessionJob = Job()
        val sessionScope = CoroutineScope(Dispatchers.IO + sessionJob)

        val sendJob = sessionScope.launch { sendLoop(bos) }
        val recvJob = sessionScope.launch { receiveLoop(decoder, pingTimestamp) }
        val pingJob = sessionScope.launch { pingLoop(bos, pingTimestamp) }
        val flushJob = sessionScope.launch { flushLoop(bos) }

        try {
            select<Unit> {
                sendJob.onJoin { }
                recvJob.onJoin { }
            }
        } finally {
            withContext(NonCancellable) {
                sessionJob.cancel()
                closeFd(fd)
                sendJob.join()
                recvJob.join()
                pingJob.join()
                flushJob.join()
            }
        }
    }

    private suspend fun sendLoop(bos: BufferedOutputStream) {
        AppLogger.d(TAG, "sendLoop started")
        try {
            for (rawPacket in outbound) {
                val connId = connIdCounter.getAndIncrement()
                val frame = PacketEncoder.encode(connId, rawPacket, Constants.MSG_DATA)

                synchronized(bos) {
                    bos.write(frame)
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
                AppLogger.d(TAG, "→ relay PING")
            }
        } catch (_: IOException) {
            AppLogger.w(TAG, "pingLoop: stream gone")
        } finally {
            AppLogger.d(TAG, "pingLoop stopped")
        }
    }

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
            AppLogger.w(TAG, "receiveLoop: ${e.message}")
        } finally {
            AppLogger.d(TAG, "receiveLoop stopped")
        }
    }

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

    private fun reconnectDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 4)
        return (Constants.RECONNECT_DELAY_MS * (1L shl exponent))
            .coerceAtMost(Constants.MAX_RECONNECT_DELAY_MS)
    }

    companion object {
        private const val FLUSH_THRESHOLD = 512
        private const val FLUSH_INTERVAL_MS = 2L
        private const val SEND_BUF_SIZE = 32_768
        private const val PING_INTERVAL_MS = 2_000L
    }
}
