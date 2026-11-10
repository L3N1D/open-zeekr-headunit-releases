package com.lenid.zoh.connection.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessSelectionPolicyTest {

    @Test
    fun `an automatic bring-up is refused when wireless is not a chosen connection mode`() {
        assertTrue(
            WirelessSelectionPolicy.refusesBringUp(wirelessSelected = false, userRequested = false)
        )
    }

    @Test
    fun `the WiFi button arms the stack on the same unit`() {
        assertFalse(
            WirelessSelectionPolicy.refusesBringUp(wirelessSelected = false, userRequested = true)
        )
    }

    @Test
    fun `nothing is refused once wireless is chosen`() {
        assertFalse(
            WirelessSelectionPolicy.refusesBringUp(wirelessSelected = true, userRequested = false)
        )
        assertFalse(
            WirelessSelectionPolicy.refusesBringUp(wirelessSelected = true, userRequested = true)
        )
    }
}
