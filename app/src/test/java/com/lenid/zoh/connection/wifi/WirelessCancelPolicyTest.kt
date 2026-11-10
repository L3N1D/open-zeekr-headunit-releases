package com.lenid.zoh.connection.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessCancelPolicyTest {

    @Test
    fun `an automatic bring-up is refused after the X`() {
        assertTrue(
            WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = false)
        )
    }

    /**
     * It passes force, never userRequested, and the ACL that raises it is often the cancelled
     * attempt's own poke. Refusing it is the decision, not an omission.
     */
    @Test
    fun `the Bluetooth auto-start does not lift it`() {
        assertTrue(
            WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = false)
        )
    }

    @Test
    fun `the WiFi button lifts it`() {
        assertFalse(
            WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = true)
        )
    }

    @Test
    fun `nothing is refused when the X was never pressed`() {
        assertFalse(
            WirelessCancelPolicy.refusesBringUp(cancelledByUser = false, userRequested = false)
        )
        assertFalse(
            WirelessCancelPolicy.refusesBringUp(cancelledByUser = false, userRequested = true)
        )
    }
}
