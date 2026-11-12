package com.shilapi.xcertplay.transport
import org.junit.Assert.*
import org.junit.Test
import java.io.*
class LabBridgeFramingTest {
    @Test fun fragmentedAndCoalescedMessagesKeepBoundaries() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(65536) { it.toByte() }
        DataOutputStream(out).let { LabBridgeFraming.write(it, payload); LabBridgeFraming.write(it, byteArrayOf(7)) }
        val fragmented = object : FilterInputStream(ByteArrayInputStream(out.toByteArray())) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len))
        }
        val input = DataInputStream(fragmented)
        assertArrayEquals(payload, LabBridgeFraming.read(input))
        assertArrayEquals(byteArrayOf(7), LabBridgeFraming.read(input))
        assertEquals(-1, input.read())
    }
    @Test fun rejectsInvalidSizesBeforeAllocatingPayload() {
        for (size in listOf(-1, 0, LabBridgeFraming.MAX_BYTES + 1, Int.MAX_VALUE)) {
            val out = ByteArrayOutputStream(); DataOutputStream(out).writeInt(size)
            assertThrows(IOException::class.java) { LabBridgeFraming.read(DataInputStream(ByteArrayInputStream(out.toByteArray()))) }
        }
    }
    @Test fun truncatedPacketFailsInsteadOfReturningPartialData() {
        assertThrows(EOFException::class.java) {
            LabBridgeFraming.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0,0,0,2,1))))
        }
    }
}
