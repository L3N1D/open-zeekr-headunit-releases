package com.lenid.zoh.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStopWaitPolicyTest {

    @Test
    fun `a session on a wireless launcher is waited for`() {
        assertTrue(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                sessionConnected = true,
                wirelessLauncherActive = true,
            )
        )
    }

    @Test
    fun `no session means nothing is torn down`() {
        assertFalse(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                sessionConnected = false,
                wirelessLauncherActive = true,
            )
        )
    }

    @Test
    fun `a wired session holds no network to give back`() {
        assertFalse(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                sessionConnected = true,
                wirelessLauncherActive = false,
            )
        )
    }

    @Test
    fun `an idle service stops at once`() {
        assertFalse(
            ServiceStopWaitPolicy.waitsForWirelessTeardown(
                sessionConnected = false,
                wirelessLauncherActive = false,
            )
        )
    }

    @Test
    fun `the wait is bounded`() {
        assertTrue(ServiceStopWaitPolicy.TEARDOWN_TIMEOUT_MS in 1L..10_000L)
    }

    @Test
    fun `only both together wait`() {
        var waits = 0
        for (session in listOf(false, true)) {
            for (launcher in listOf(false, true)) {
                if (ServiceStopWaitPolicy.waitsForWirelessTeardown(session, launcher)) waits++
            }
        }
        assertEquals(1, waits)
    }
}
