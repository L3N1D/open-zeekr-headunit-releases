package com.lenid.zoh.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectGracePolicyTest {

    @Test
    fun `a wired link with nothing coming back ends exactly where it always did`() {
        assertTrue(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 7_999, sinceProgressMs = null))
        assertFalse(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 8_000, sinceProgressMs = null))
        assertEquals(
            ReconnectGracePolicy.Ended.BASE_EXPIRED,
            ReconnectGracePolicy.endedBecause(isUsb = true, sinceDisconnectMs = 8_000, sinceProgressMs = null)
        )
    }

    @Test
    fun `a wireless link with nothing coming back ends exactly where it always did`() {
        assertTrue(ReconnectGracePolicy.keepWaiting(isUsb = false, sinceDisconnectMs = 19_999, sinceProgressMs = null))
        assertFalse(ReconnectGracePolicy.keepWaiting(isUsb = false, sinceDisconnectMs = 20_000, sinceProgressMs = null))
    }

    @Test
    fun `a switch that started before the base carries the wait past it`() {
        // The reported case: the phone re-enumerates at 6s and the AOA switch is still running at 8s.
        assertTrue(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 8_000, sinceProgressMs = 2_000))
        assertTrue(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 18_000, sinceProgressMs = 12_000))
        assertNull(ReconnectGracePolicy.endedBecause(isUsb = true, sinceDisconnectMs = 18_000, sinceProgressMs = 12_000))
    }

    @Test
    fun `a sign of life that went stale ends the wait`() {
        assertFalse(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 20_000, sinceProgressMs = 14_000))
        assertEquals(
            ReconnectGracePolicy.Ended.PROGRESS_WENT_STALE,
            ReconnectGracePolicy.endedBecause(isUsb = true, sinceDisconnectMs = 20_000, sinceProgressMs = 14_000)
        )
    }

    @Test
    fun `a fresh sign of life before the base never shortens it`() {
        // Progress cannot end a wait the base is still holding open, whichever way round they fall.
        assertTrue(ReconnectGracePolicy.keepWaiting(isUsb = false, sinceDisconnectMs = 15_000, sinceProgressMs = 14_500))
    }

    @Test
    fun `a signal on every tick still stops at the ceiling`() {
        assertTrue(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 44_999, sinceProgressMs = 0))
        assertFalse(ReconnectGracePolicy.keepWaiting(isUsb = true, sinceDisconnectMs = 45_000, sinceProgressMs = 0))
        assertEquals(
            ReconnectGracePolicy.Ended.CEILING_REACHED,
            ReconnectGracePolicy.endedBecause(isUsb = true, sinceDisconnectMs = 45_000, sinceProgressMs = 0)
        )
    }

    @Test
    fun `the ceiling outlasts a worst-case switch that starts at the base`() {
        // Otherwise the bound meant to stop a runaway wait would cut off the case it exists for.
        assertTrue(ReconnectGracePolicy.CEILING_MS >= ReconnectGracePolicy.BASE_USB_MS + ReconnectGracePolicy.PROGRESS_GRACE_MS)
        assertTrue(ReconnectGracePolicy.CEILING_MS >= ReconnectGracePolicy.BASE_WIRELESS_MS + ReconnectGracePolicy.PROGRESS_GRACE_MS)
    }
}
