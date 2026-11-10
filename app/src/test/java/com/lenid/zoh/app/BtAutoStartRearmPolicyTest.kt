package com.lenid.zoh.app

import com.lenid.zoh.connection.wifi.WifiLauncherMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtAutoStartRearmPolicyTest {

    private fun actions(
        mode: WifiLauncherMode = WifiLauncherMode.NATIVE,
        wirelessSelected: Boolean = true,
        sessionUp: Boolean = false,
        wirelessArmed: Boolean = false,
        handshakeActive: Boolean? = null,
        attemptInFlight: Boolean? = null,
        groupUp: Boolean? = null,
        networkComingUp: Boolean? = false
    ) = BtAutoStartRearmPolicy.actionsFor(
        mode = mode,
        wirelessSelected = wirelessSelected,
        sessionUp = sessionUp,
        wirelessArmed = wirelessArmed,
        handshakeActive = handshakeActive,
        attemptInFlight = attemptInFlight,
        groupUp = groupUp,
        networkComingUp = networkComingUp
    )

    private val otherWirelessModes = WifiLauncherMode.entries.filter { it != WifiLauncherMode.NATIVE }

    /**
     * The state this policy exists for: a Native user exit nulled the launcher and the phone is
     * back. Null must read as "nothing running", not as a veto, or the mode stays dead for good.
     */
    @Test
    fun `a user exit with the launcher nulled still re-arms when the phone comes back`() {
        val actions = actions(mode = WifiLauncherMode.NATIVE, wirelessArmed = false, groupUp = true)
        assertTrue(actions.forceRearmWireless)
        assertTrue(actions.clearUserExit)
    }

    @Test
    fun `only the Native mode setting forces a re-arm`() {
        for (mode in WifiLauncherMode.entries) {
            val expected = mode == WifiLauncherMode.NATIVE
            assertEquals("mode=$mode", expected, actions(mode = mode).forceRearmWireless)
        }
    }

    /**
     * A handoff closes the AA listeners, so the handshake reads inactive all session. A later
     * ACL_CONNECTED must not tear a working session down to re-arm it, in any mode.
     */
    @Test
    fun `a live or connecting session is never torn down by an ACL_CONNECTED`() {
        for (mode in WifiLauncherMode.entries) {
            assertTrue("mode=$mode", actions(mode = mode, sessionUp = true).doesNothing)
        }
    }

    @Test
    fun `a live session is never torn down even with no group`() {
        assertTrue(actions(sessionUp = true, handshakeActive = false, groupUp = false).doesNothing)
    }

    @Test
    fun `an active handshake with a live group is left alone`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = true).doesNothing)
    }

    @Test
    fun `an attempt in flight is left alone`() {
        assertTrue(actions(handshakeActive = false, attemptInFlight = true).doesNothing)
    }

    /**
     * The state every reconnect used to pay for: armed, listening and a group up, and the phone's
     * arrival rebuilt all of it. Our own poke's ACL echo lands here too, which is the self-wake loop.
     */
    @Test
    fun `an armed Native mode with a live group is left alone`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = true).doesNothing)
    }

    /** The join watchdog gave up, or WiFi went off: the listeners are open with nothing to join. */
    @Test
    fun `an armed Native mode whose group is gone is re-armed`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = false).forceRearmWireless)
    }

    /** The hotspot transport has no group of ours to ask about, so the listeners decide. */
    @Test
    fun `a route with no group to ask about is left alone when its handshake is active`() {
        assertTrue(actions(handshakeActive = true, attemptInFlight = false, groupUp = null).doesNothing)
    }

    @Test
    fun `a route with no group to ask about is re-armed when its handshake is quiet`() {
        assertTrue(actions(handshakeActive = false, attemptInFlight = false, groupUp = null).forceRearmWireless)
    }

    /**
     * A launcher that exists but is fully quiet re-arms too: on a cold start the running handshake
     * blocks this, so a quiet one means something already stopped.
     */
    @Test
    fun `a present but quiet launcher does not block the Native re-arm`() {
        val actions = actions(wirelessArmed = true, handshakeActive = false, attemptInFlight = false)
        assertTrue(actions.forceRearmWireless)
    }

    @Test
    fun `the other wireless modes arm only when nothing is armed`() {
        for (mode in otherWirelessModes) {
            val actions = actions(mode = mode, wirelessArmed = false)
            assertTrue("mode=$mode", actions.armWirelessIfIdle)
            assertFalse("mode=$mode", actions.forceRearmWireless)
        }
    }

    @Test
    fun `a healthy group is never torn down to re-arm it`() {
        for (mode in otherWirelessModes) {
            val actions = actions(mode = mode, wirelessArmed = true)
            assertFalse("mode=$mode", actions.armWirelessIfIdle)
            assertFalse("mode=$mode", actions.forceRearmWireless)
            assertTrue("mode=$mode", actions.clearUserExit)
        }
    }

    /** The default wireless mode is Helper; a Self-only user must not get a Helper group armed. */
    @Test
    fun `a Self-only user gets no wireless launcher armed`() {
        for (mode in otherWirelessModes) {
            val actions = actions(mode = mode, wirelessSelected = false)
            assertFalse("mode=$mode", actions.armWirelessIfIdle)
            assertTrue("mode=$mode", actions.clearUserExit)
        }
    }

    /** A cable-only unit gets no network created and no phone poked by an arrival it did not ask for. */
    @Test
    fun `Native does not re-arm when wireless is not a chosen connection mode`() {
        val actions = actions(mode = WifiLauncherMode.NATIVE, wirelessSelected = false)
        assertFalse(actions.forceRearmWireless)
        assertFalse(actions.armWirelessIfIdle)
    }



    /**
     * The field failure: in Native mode the phone whose Bluetooth arrived is the source of the
     * wireless session, so its head unit server is not running and the launch cannot win. It armed
     * Self Mode anyway, and the failure stopped the wireless launcher mid-bring-up.
     */

    /** Native stored but wireless not selected leaves Self Mode as the only thing that could serve. */

    /** Every other mode is unchanged: none of them dials our own loopback out from under itself. */

    /**
     * The poke's own socket.connect() raises the ACL_CONNECTED that brought us here, and
     * isAttemptInFlight() is the one signal that says so. Null means Native is not armed.
     */

    /**
     * The window this policy could not see: a network asked for and not yet answered reads as
     * "cannot accept" everywhere else, and rebuilding there starts a second create underneath.
     */
    @Test
    fun `a network still being created is left to answer`() {
        for (mode in WifiLauncherMode.entries) {
            assertTrue(
                "mode=$mode",
                actions(mode = mode, handshakeActive = false, attemptInFlight = false, groupUp = false, networkComingUp = true).doesNothing
            )
        }
    }

    /** The hotspot route has no network of ours, so the create question does not apply there. */
    @Test
    fun `a route with no network of ours to ask about still re-arms`() {
        val actions = actions(mode = WifiLauncherMode.NATIVE, groupUp = null, networkComingUp = null)
        assertTrue(actions.forceRearmWireless)
        assertTrue(actions.clearUserExit)
    }


    @Test
    fun `a vetoed arrival names the veto that fired`() {
        assertEquals(
            "a session is already up",
            BtAutoStartRearmPolicy.vetoReason(true, null, null, null, null)
        )
        assertEquals(
            "a handshake attempt is already in flight",
            BtAutoStartRearmPolicy.vetoReason(false, null, true, null, null)
        )
        assertEquals(
            "the network has been asked for and has not answered yet",
            BtAutoStartRearmPolicy.vetoReason(false, null, false, null, true)
        )
        assertEquals(
            "a handshake is running on a group that is still up",
            BtAutoStartRearmPolicy.vetoReason(false, true, false, true, false)
        )
    }

    @Test
    fun `an arrival with nothing in the way has no veto`() {
        assertNull(BtAutoStartRearmPolicy.vetoReason(false, false, false, true, false))
    }

    @Test
    fun `a stranded handshake with no network is not a veto`() {
        assertNull(BtAutoStartRearmPolicy.vetoReason(false, true, false, false, false))
    }
}
