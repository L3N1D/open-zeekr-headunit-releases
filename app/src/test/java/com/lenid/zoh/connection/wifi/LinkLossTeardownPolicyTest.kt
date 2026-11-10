package com.lenid.zoh.connection.wifi

import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkLossTeardownPolicyTest {








    /**
     * A wired session quiesces the wireless stack, so `active` is null. A shutdown in that window
     * still has a session to close, and the early return that used to guard this call site skipped
     * the whole orderly teardown - leaving the phone's head unit server holding a peer that never
     * came back, which is the exact failure this policy exists to prevent.
     */
    @Test
    fun `a shutdown with no wireless route armed still tears the session down`() {
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.DEVICE_SHUTDOWN, launcher = null, sessionIsWireless = false
            )
        )
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.DEVICE_SHUTDOWN, launcher = null, sessionIsWireless = true
            )
        )
    }

    @Test
    fun `WiFi going away with no wireless route armed is decided by the session alone`() {
        // Nothing of ours is hosting a network, so there is none of ours to outlive the toggle.
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING, launcher = null, sessionIsWireless = true
            )
        )
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING, launcher = null, sessionIsWireless = false
            )
        )
    }




    @Test
    fun `ACC-off needs one of the two signals, and neither means nothing happens`() {
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.ACC_POWER_LOST, launcher = null,
                peerIsHeadUnitServer = false, accSignalIsExplicit = false
            )
        )
    }

}
