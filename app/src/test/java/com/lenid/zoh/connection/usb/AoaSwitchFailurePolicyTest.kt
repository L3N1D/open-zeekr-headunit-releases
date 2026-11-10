package com.lenid.zoh.connection.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AoaSwitchFailurePolicyTest {

    @Test
    fun `a stall is named as a refusal, which is the whole diagnosis`() {
        val text = AoaSwitchFailurePolicy.describe(-9)
        assertTrue(text, text.contains("stalled"))
        assertTrue(text, text.contains("not offering Android Auto over USB"))
    }

    @Test
    fun `a timeout is not a refusal`() {
        val timeout = AoaSwitchFailurePolicy.describe(-7)
        assertTrue(timeout, timeout.contains("in time"))
        assertTrue(timeout != AoaSwitchFailurePolicy.describe(-9))
    }

    @Test
    fun `our own pre-flight failures do not read as libusb errors`() {
        assertEquals(
            "the device could not be opened for the switch",
            AoaSwitchFailurePolicy.describe(AoaSwitchFailurePolicy.NO_HANDLE),
        )
        assertEquals(
            "the switch call itself failed",
            AoaSwitchFailurePolicy.describe(AoaSwitchFailurePolicy.CALL_FAILED),
        )
        // The sentinels exist so neither can be read as LIBUSB_ERROR_IO.
        assertTrue(AoaSwitchFailurePolicy.NO_HANDLE != -1)
        assertTrue(AoaSwitchFailurePolicy.CALL_FAILED != -1)
    }

    @Test
    fun `a disconnect during the switch is named`() {
        assertEquals(
            AoaSwitchFailurePolicy.describe(-4),
            AoaSwitchFailurePolicy.describe(-5),
        )
        assertTrue(AoaSwitchFailurePolicy.describe(-4).contains("left the bus"))
    }

    @Test
    fun `an unmapped code still carries its number`() {
        assertEquals("libusb error -99", AoaSwitchFailurePolicy.describe(-99))
    }

    @Test
    fun `the failure line carries both the sentence and the raw code`() {
        val line = AoaSwitchFailurePolicy.failureLine(-9)
        assertTrue(line, line.contains("stalled"))
        assertTrue(line, line.contains("(code -9)"))
    }
}
