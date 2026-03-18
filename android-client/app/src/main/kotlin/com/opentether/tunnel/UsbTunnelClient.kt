package com.opentether.tunnel

import android.net.VpnService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.opentether.Constants
import com.opentether.StatsHolder
import com.opentether.model.OtpFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "OT/UsbTunnelClient"

class UsbTunnelClient(
    private val outbound:   Channel<ByteArray>,
    private val inbound:    Channel<ByteArray>,
    private val vpnService: VpnService,
) {
    private val connIdCounter = AtomicInteger(1)

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        Log.i(TAG, "started — will connect to ${Constants.RELAY_HOST}:${Constants.RELAY_PORT}")

        while (isActive) {
            Log.i(TAG, "connecting to relay...")

            var fd: FileDescriptor? = null
            try {
                fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)

                val intFd = intFd(fd)
                if (intFd == -1) {
                    Log.e(TAG, "could not read native fd — will retry")
                    delay(Constants.RECONNECT_DELAY_MS)
                    continue
                }

                if (!vpnService.protect(intFd)) {
                    Log.e(TAG, "protect(int) returned false — will retry")
                    delay(Constants.RECONNECT_DELAY_MS)
                    continue
                }

                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1)
                Os.connect(fd, InetAddress.getByName(Constants.RELAY_HOST), Constants.RELAY_PORT)
                Log.i(TAG, "connected to relay")

                val input  = DataInputStream(FileInputStream(fd))
                val output = FileOutputStream(fd)
                runSession(scope, fd, input, output)

            } catch (e: ErrnoException) {
                if (!isActive) return@launch
                Log.w(TAG, "OS error: ${e.message} (errno=${e.errno})")
            } catch (e: IOException) {
                if (!isActive) return@launch
                Log.w(TAG, "IO error: ${e.message}")
            } catch (e: Exception) {
                if (!isActive) return@launch
                Log.e(TAG, "unexpected: ${e.message}")
            } finally {
                fd?.let { closeFd(it) }
            }

            if (!isActive) return@launch
            Log.i(TAG, "disconnected — retrying in ${Constants.RECONNECT_DELAY_MS}ms")
            delay(Constants.RECONNECT_DELAY_MS)
        }
    }

    private suspend fun runSession(
        scope:  CoroutineScope,
        fd:     FileDescriptor,
        input:  DataInputStream,
        output: FileOutputStream,
    ) {
        val decoder      = PacketDecoder(input)
        val sessionScope = CoroutineScope(scope.coroutineContext)

        val sendJob = sessionScope.launch(Dispatchers.IO) { sendLoop(output) }
        val recvJob = sessionScope.launch(Dispatchers.IO) { receiveLoop(decoder) }

        try {
            sendJob.join()
        } finally {
            sendJob.cancel()
            recvJob.cancel()
            closeFd(fd)
            recvJob.join()
        }
    }

    private suspend fun sendLoop(out: OutputStream) {
        Log.d(TAG, "sendLoop started")
        try {
            for (rawPacket in outbound) {
                val connId = connIdCounter.getAndIncrement()
                val frame  = PacketEncoder.encode(connId, rawPacket, Constants.MSG_DATA)
                out.write(frame)

                // Increment shared counters — the service ticker will sample these
                StatsHolder.bytesUpSec.addAndGet(rawPacket.size.toLong())
                StatsHolder.totalUp.addAndGet(rawPacket.size.toLong())

                Log.d(TAG, "→ relay ${rawPacket.size}B  conn_id=$connId")
            }
        } catch (e: IOException) {
            Log.w(TAG, "sendLoop: ${e.message}")
        } finally {
            Log.d(TAG, "sendLoop stopped")
        }
    }

    private suspend fun receiveLoop(decoder: PacketDecoder) {
        Log.d(TAG, "receiveLoop started")
        try {
            while (true) {
                val frame: OtpFrame = decoder.readFrame()
                when (frame.msgType) {
                    Constants.MSG_DATA -> {
                        val payload = frame.payload ?: continue
                        Log.d(TAG, "← relay ${payload.size}B  conn_id=${frame.connId}")

                        // Increment shared counters
                        StatsHolder.bytesDownSec.addAndGet(payload.size.toLong())
                        StatsHolder.totalDown.addAndGet(payload.size.toLong())

                        inbound.send(payload)
                    }
                    Constants.MSG_PING  -> Log.d(TAG, "← relay PING")
                    Constants.MSG_CLOSE -> Log.d(TAG, "← relay CLOSE conn_id=${frame.connId}")
                    Constants.MSG_ERROR -> {
                        val msg = frame.payload?.let { String(it, Charsets.UTF_8) } ?: "(none)"
                        Log.e(TAG, "← relay ERROR: $msg")
                    }
                    else -> Log.w(TAG, "← unknown type 0x${frame.msgType.toString(16)}")
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "receiveLoop: ${e.message}")
        } catch (e: SecurityException) {
            Log.e(TAG, "receiveLoop: malformed frame: ${e.message}")
        } finally {
            Log.d(TAG, "receiveLoop stopped")
        }
    }

    private fun intFd(fd: FileDescriptor): Int = try {
        val f = FileDescriptor::class.java.getDeclaredField("descriptor")
        f.isAccessible = true
        f.getInt(fd)
    } catch (e: Exception) {
        Log.e(TAG, "intFd reflection failed: ${e.message}")
        -1
    }

    private fun closeFd(fd: FileDescriptor) {
        try { Os.close(fd) } catch (_: Exception) {}
    }
}