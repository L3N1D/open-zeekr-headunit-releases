package com.lenid.zoh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneExitQuietPolicyTest {

    /** The measured case: the listeners reopen and the pill claimed a reconnect 119 ms later. */
    @Test
    fun `the pill stays down just after a clean phone exit`() {
        assertTrue(PhoneExitQuietPolicy.suppressesPill(phoneLeftAtMs = 1_000L, nowMs = 1_119L))
    }

    @Test
    fun `the window ends`() {
        assertFalse(
            PhoneExitQuietPolicy.suppressesPill(
                phoneLeftAtMs = 1_000L, nowMs = 1_000L + PhoneExitQuietPolicy.QUIET_MS
            )
        )
    }

    @Test
    fun `no phone exit suppresses nothing`() {
        assertFalse(PhoneExitQuietPolicy.suppressesPill(phoneLeftAtMs = 0L, nowMs = 9_999L))
    }

    /** elapsedRealtime does not go backwards, but a stamp from before a reboot would. */
    @Test
    fun `a stamp in the future suppresses nothing`() {
        assertFalse(PhoneExitQuietPolicy.suppressesPill(phoneLeftAtMs = 5_000L, nowMs = 1_000L))
    }

    @Test
    fun `the remaining window is what the re-render waits for`() {
        assertEquals(3_000L, PhoneExitQuietPolicy.remainingMs(phoneLeftAtMs = 1_000L, nowMs = 2_000L))
        assertEquals(0L, PhoneExitQuietPolicy.remainingMs(phoneLeftAtMs = 1_000L, nowMs = 90_000L))
    }
}
