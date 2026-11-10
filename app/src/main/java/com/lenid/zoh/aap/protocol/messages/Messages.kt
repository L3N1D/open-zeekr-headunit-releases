package com.lenid.zoh.aap.protocol.messages

import com.lenid.zoh.aap.AapVersionNegotiation
import com.lenid.zoh.utils.Utils

object Messages {
    const val DEF_BUFFER_LENGTH = 131080

    val versionRequest: ByteArray
        get() = createRawMessage(0, 3, 1, VERSION_REQUEST, VERSION_REQUEST.size)

    // byte ac_buf [] = {0, 3, 0, 4, 0, 4, 8, 0};
    val statusOk: ByteArray
        get() = createRawMessage(0, 3, 4, byteArrayOf(8, 0), 2)

    fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray): ByteArray =
            createRawMessage(chan, flags, type, data, data.size)

    private var VERSION_REQUEST = byteArrayOf(
        0, AapVersionNegotiation.ANNOUNCED_MAJOR.toByte(), 0, AapVersionNegotiation.ANNOUNCED_MINOR.toByte()
    )

    private fun createRawMessage(chan: Int, flags: Int, type: Int, data: ByteArray, size: Int): ByteArray {

        val total = 6 + size
        val buffer = ByteArray(total)

        buffer[0] = chan.toByte()
        buffer[1] = flags.toByte()
        Utils.intToBytes(size + 2, 2, buffer)
        Utils.intToBytes(type, 4, buffer)

        System.arraycopy(data, 0, buffer, 6, size)
        return buffer
    }
}
