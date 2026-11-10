package com.lenid.zoh.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtAutoDisconnectPolicyTest {

    private val car = "AA:BB:CC:DD:EE:01"
    private val phone = "AA:BB:CC:DD:EE:02"
    private val watched = setOf(car)

    @Test
    fun `an unwatched device is ignored whichever way its link goes`() {
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(watched, phone, connected = false))
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(watched, phone, connected = true))
    }

    /** The device extra on the broadcast can be null; that is not a watched device leaving. */
    @Test
    fun `a null device address is ignored`() {
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(watched, null, connected = false))
    }

    /** The empty list is the off switch, as it is for the auto-start list. */
    @Test
    fun `an empty watch list ignores everything`() {
        assertEquals(BtAutoDisconnectArm.IGNORE, BtAutoDisconnectPolicy.onAclEvent(emptySet(), car, connected = false))
    }

    @Test
    fun `a watched device losing its link arms the timer`() {
        assertEquals(BtAutoDisconnectArm.ARM, BtAutoDisconnectPolicy.onAclEvent(watched, car, connected = false))
    }

    @Test
    fun `a watched device coming back cancels the timer`() {
        assertEquals(BtAutoDisconnectArm.CANCEL, BtAutoDisconnectPolicy.onAclEvent(watched, car, connected = true))
    }

    @Test
    fun `nothing is ended when nothing is projecting`() {
        assertFalse(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = false, deviceCameBack = false, msSinceOwnSocketClose = null))
    }

    /**
     * The Native handoff case: the handshake socket closes seconds into the session and the OS
     * reports the phone's link gone. A loss that follows our own close is that close, not the phone.
     */
    @Test
    fun `a loss inside our own socket-close grace is left alone`() {
        val grace = BtAutoDisconnectPolicy.OWN_SOCKET_CLOSE_GRACE_MS
        assertFalse(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, deviceCameBack = false, msSinceOwnSocketClose = grace - 1))
        assertTrue(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, deviceCameBack = false, msSinceOwnSocketClose = grace))
    }

    /** A transport that never opens a Bluetooth socket to the phone gets no grace, and needs none. */
    @Test
    fun `a loss with no own close behind it ends the session`() {
        assertTrue(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, deviceCameBack = false, msSinceOwnSocketClose = null))
    }

    @Test
    fun `a device that came back during the grace delay saves the session`() {
        assertFalse(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, deviceCameBack = true, msSinceOwnSocketClose = null))
    }

    @Test
    fun `a watched device that stays away ends a settled session`() {
        assertTrue(BtAutoDisconnectPolicy.shouldEndSession(sessionUp = true, deviceCameBack = false, msSinceOwnSocketClose = 600_000L))
    }

    @Test
    fun `zero seconds means immediately and an absurd delay is clamped`() {
        assertEquals(0L, BtAutoDisconnectPolicy.graceDelayMs(0))
        assertEquals(0L, BtAutoDisconnectPolicy.graceDelayMs(-5))
        assertEquals(5_000L, BtAutoDisconnectPolicy.graceDelayMs(5))
        assertEquals(3_600_000L, BtAutoDisconnectPolicy.graceDelayMs(99_999))
    }
}
