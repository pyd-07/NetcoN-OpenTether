package com.opentether.model

import com.opentether.Constants

data class OtpFrame(
    val connId:  Int,
    val msgType: Byte,
    val flags:   Byte,
    val payload: ByteArray?,
) {
    val isData: Boolean
        get() = msgType == Constants.MSG_DATA && payload != null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtpFrame) return false
        return connId  == other.connId  &&
               msgType == other.msgType &&
               flags   == other.flags   &&
               payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = connId
        result = 31 * result + msgType
        result = 31 * result + flags
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEquals(other: ByteArray?): Boolean =
    if (this == null && other == null) true
    else if (this == null || other == null) false
    else this.contentEquals(other)
