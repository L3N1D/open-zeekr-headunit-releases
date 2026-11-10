package com.lenid.zoh.utils

import com.lenid.zoh.utils.DummyVpnPolicy.Owner
import com.lenid.zoh.utils.DummyVpnPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DummyVpnPolicyTest {

    // ---------------------------------------------------------------------
    // shouldStop
    // ---------------------------------------------------------------------


    @Test
    fun `a VPN we did not start is never stopped`() {
        for (reason in Reason.entries) {
            assertFalse(
                "$reason must not stop a VPN with no owner",
                DummyVpnPolicy.shouldStop(owner = null, reason = reason)
            )
        }
    }



    @Test
    fun `the end of a session stops either owner`() {
        for (owner in Owner.entries) {
            assertTrue(DummyVpnPolicy.shouldStop(owner, Reason.SESSION_ENDED))
        }
    }

    @Test
    fun `destroying the service stops anything we own`() {
        for (owner in Owner.entries) {
            assertTrue(DummyVpnPolicy.shouldStop(owner, Reason.SERVICE_DESTROYED))
        }
    }

    // ---------------------------------------------------------------------
    // shouldStartForSession
    // ---------------------------------------------------------------------

    private fun start(
        keepDuringSession: Boolean = true,
        nativeWirelessSession: Boolean = true,
        currentOwner: Owner? = null,
        vpnAvailable: Boolean = true,
        alreadyPrepared: Boolean = true,
    ) = DummyVpnPolicy.shouldStartForSession(
        keepDuringSession = keepDuringSession,
        nativeWirelessSession = nativeWirelessSession,
        currentOwner = currentOwner,
        vpnAvailable = vpnAvailable,
        alreadyPrepared = alreadyPrepared,
    )


}
