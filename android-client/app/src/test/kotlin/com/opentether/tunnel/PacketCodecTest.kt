package com.opentether.tunnel

import com.opentether.Constants
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Literal wire-format tests. Expected bytes are hard-coded and must not be
 * produced by PacketEncoder; the Go relay has the same vectors.
 */
class PacketCodecTest {

    private fun decoderFrom(bytes: ByteArray): PacketDecoder =
        PacketDecoder(DataInputStream(ByteArrayInputStream(bytes)))

    private fun decoder(vararg bytes: Byte): PacketDecoder = decoderFrom(bytes)

    @Test
    fun encodeGoldenData() {
        val got = PacketEncoder.encode(
            connId = 0x01020304,
            payload = "ABC".toByteArray(Charsets.US_ASCII),
            msgType = Constants.MSG_DATA,
            flags = Constants.FLAG_SYN,
        )
        val want = byteArrayOf(
            0x01, 0x02, 0x03, 0x04,
            0x00, 0x00, 0x00, 0x03,
            0x01,
            0x02,
            0x00, 0x00,
            0x41, 0x42, 0x43,
        )
        assertArrayEquals(want, got)
    }

    @Test
    fun encodeGoldenZeroPong() {
        val got = PacketEncoder.encodeControl(connId = 0, msgType = Constants.MSG_PONG)
        val want = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x06,
            0x00,
            0x00, 0x00,
        )
        assertArrayEquals(want, got)
    }

    // 65_535 is the Go relay's MaxPayloadSize and 0x0000FFFF is the largest
    // payload_length the decoder accepts. The literal header matches
    // internal/protocol/protocol_golden_test.go.
    @Test
    fun encodeMaxPayload() {
        val payload = ByteArray(MAX_PAYLOAD) { 0xAB.toByte() }
        val got = PacketEncoder.encode(
            connId = 1,
            payload = payload,
            msgType = Constants.MSG_DATA,
        )

        assertEquals(Constants.FRAME_HEADER_SIZE + MAX_PAYLOAD, got.size)
        assertArrayEquals(
            maxPayloadHeader(),
            got.copyOfRange(0, Constants.FRAME_HEADER_SIZE),
        )
        assertArrayEquals(
            payload,
            got.copyOfRange(Constants.FRAME_HEADER_SIZE, got.size),
        )
    }

    @Test
    fun decodeMaxPayload() {
        val payload = ByteArray(MAX_PAYLOAD) { 0xAB.toByte() }
        val frame = decoderFrom(maxPayloadHeader() + payload).readFrame()
        val decoded = frame.payload ?: error("max-payload frame lost its payload")

        assertEquals(1, frame.connId)
        assertEquals(Constants.MSG_DATA, frame.msgType)
        assertEquals(MAX_PAYLOAD, decoded.size)
        assertArrayEquals(payload, decoded)
    }

    @Test
    fun decodeGoldenData() {
        val frame = decoder(
            0x01, 0x02, 0x03, 0x04,
            0x00, 0x00, 0x00, 0x03,
            0x01,
            0x02,
            0x00, 0x00,
            0x41, 0x42, 0x43,
        ).readFrame()

        assertEquals(0x01020304, frame.connId)
        assertEquals(Constants.MSG_DATA, frame.msgType)
        assertEquals(Constants.FLAG_SYN, frame.flags)
        assertArrayEquals("ABC".toByteArray(Charsets.US_ASCII), frame.payload)
    }

    @Test
    fun decodeZeroPayloadControlFrame() {
        val frame = decoder(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x06,
            0x00,
            0x00, 0x00,
        ).readFrame()

        assertNull(frame.payload)
        assertEquals(Constants.MSG_PONG, frame.msgType)
        assertFalse(frame.isData)
    }

    @Test
    fun decodeRejectsFirstUnsupportedLength() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
        )
        assertThrows(IOException::class.java) { decoder(*bytes).readFrame() }
    }

    @Test
    fun decodeRejectsAllOnesLength() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x01, 0x00, 0x00, 0x00,
        )
        assertThrows(IOException::class.java) { decoder(*bytes).readFrame() }
    }

    @Test
    fun decodeRejectsTruncatedHeader() {
        assertThrows(EOFException::class.java) {
            decoderFrom(ByteArray(Constants.FRAME_HEADER_SIZE - 1) { 0 }).readFrame()
        }
    }

    @Test
    fun decodeRejectsTruncatedPayload() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x05,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x03,
        )
        assertThrows(EOFException::class.java) { decoder(*bytes).readFrame() }
    }

    @Test
    fun decodeIgnoresReservedBytes() {
        val frame = decoder(
            0x00, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x02,
            0x01,
            0x00,
            0xDE.toByte(), 0xAD.toByte(),
            0x41, 0x42,
        ).readFrame()

        assertArrayEquals(byteArrayOf(0x41, 0x42), frame.payload)
    }

    @Test
    fun decodePreservesUnknownTypeAndFlags() {
        val frame = decoder(
            0x00, 0x00, 0x00, 0x02,
            0x00, 0x00, 0x00, 0x01,
            0x7F,
            0xFF.toByte(),
            0x00, 0x00,
            0x42,
        ).readFrame()

        assertEquals(0x7F.toByte(), frame.msgType)
        assertEquals(0xFF.toByte(), frame.flags)
        assertArrayEquals(byteArrayOf(0x42), frame.payload)
    }

    private fun maxPayloadHeader(): ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x01, // conn_id 1
        0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), // payload_length 65535
        0x01, // MSG_DATA
        0x00, // flags
        0x00, 0x00, // reserved
    )

    private companion object {
        const val MAX_PAYLOAD = 65_535
    }
}
