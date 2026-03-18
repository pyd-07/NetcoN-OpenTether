package com.opentether.tunnel

import com.opentether.Constants
import com.opentether.model.OtpFrame
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads exactly one OTP frame per [readFrame] call from a [DataInputStream].
 *
 * Why DataInputStream?
 *   TCP is a stream — it does not preserve message boundaries. A single
 *   socket read may return part of a header, or two headers merged together.
 *   DataInputStream.readFully() loops internally until it has the exact
 *   number of bytes requested, so we always get a complete header or payload.
 *
 * This class is NOT thread-safe. Create one instance per connection and
 * call readFrame() from a single coroutine only.
 */
class PacketDecoder(private val stream: DataInputStream) {

    // Reused across calls — safe because readFrame() is called sequentially.
    private val headerBuf = ByteArray(Constants.FRAME_HEADER_SIZE)

    /**
     * Blocks until a complete frame is available and returns it.
     *
     * @throws EOFException  if the connection closed cleanly mid-read
     * @throws IOException   if the connection was reset or timed out
     * @throws SecurityException if the payload_length field exceeds [MAX_PAYLOAD]
     */
    fun readFrame(): OtpFrame {
        // Step 1: read exactly 12 header bytes — blocks until all arrive.
        stream.readFully(headerBuf)

        val buf = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN)
        val connId     = buf.int              // bytes 0–3
        val payloadLen = buf.int              // bytes 4–7
        val msgType    = buf.get()            // byte  8
        val flags      = buf.get()            // byte  9
        // buf.short                          // bytes 10–11 reserved — skip

        // Guard against a malformed or malicious frame claiming a huge payload.
        // A legitimate IP packet can never exceed 65535 bytes.
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD) {
            throw SecurityException(
                "OTP frame rejected: payload_length=$payloadLen exceeds limit $MAX_PAYLOAD"
            )
        }

        // Step 2: read exactly payload_length bytes — blocks until all arrive.
        val payload: ByteArray? = if (payloadLen > 0) {
            ByteArray(payloadLen).also { stream.readFully(it) }
        } else {
            null
        }

        return OtpFrame(
            connId  = connId,
            msgType = msgType,
            flags   = flags,
            payload = payload,
        )
    }

    companion object {
        // Hard cap matching the relay's MaxPayloadSize constant.
        private const val MAX_PAYLOAD = 65535
    }
}
