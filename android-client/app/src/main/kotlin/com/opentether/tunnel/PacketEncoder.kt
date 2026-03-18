package com.opentether.tunnel

import com.opentether.Constants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a raw IP packet into an OTP wire frame.
 *
 * Wire layout (12-byte header, big-endian):
 *
 *   bytes 0–3   connection_id  (uint32)
 *   bytes 4–7   payload_length (uint32)
 *   byte  8     msg_type       (uint8)
 *   byte  9     flags          (uint8)
 *   bytes 10–11 reserved       (uint16, always 0)
 *   bytes 12…   payload        (raw IP packet)
 *
 * This object is stateless — safe to call from multiple coroutines.
 */
object PacketEncoder {

    /**
     * Returns a single ByteArray containing the 12-byte header
     * followed immediately by [payload].
     *
     * Allocates one array per call. For the packet volumes typical
     * of a VPN (hundreds per second, not millions), this is fine.
     * If profiling shows GC pressure here, switch to a pooled buffer.
     *
     * @param connId   logical connection identifier (assigned by ConnectionManager)
     * @param payload  raw IP packet bytes
     * @param msgType  one of the MSG_* constants in [Constants]
     * @param flags    bitmask of FLAG_* constants in [Constants]; default 0
     */
    fun encode(
        connId:  Int,
        payload: ByteArray,
        msgType: Byte = Constants.MSG_DATA,
        flags:   Byte = 0,
    ): ByteArray {
        val frame = ByteBuffer
            .allocate(Constants.FRAME_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)

        frame.putInt(connId)            // bytes 0–3
        frame.putInt(payload.size)      // bytes 4–7
        frame.put(msgType)              // byte  8
        frame.put(flags)                // byte  9
        frame.putShort(0)               // bytes 10–11  reserved
        frame.put(payload)              // bytes 12…

        return frame.array()
    }

    /**
     * Encodes a control frame that carries no IP payload —
     * used for MSG_PING, MSG_CLOSE, MSG_CONNECT, etc.
     */
    fun encodeControl(
        connId:  Int  = 0,
        msgType: Byte,
        flags:   Byte = 0,
    ): ByteArray {
        val frame = ByteBuffer
            .allocate(Constants.FRAME_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)

        frame.putInt(connId)
        frame.putInt(0)          // payload_length = 0
        frame.put(msgType)
        frame.put(flags)
        frame.putShort(0)

        return frame.array()
    }
}
