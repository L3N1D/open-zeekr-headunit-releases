package com.lenid.zoh.connection.wifi

import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each launcher asks [WifiLauncherSharedServices] to run for it.
 *
 * These three predicates are the whole of the bring-up contract now, so a wrong answer is a route
 * that silently never starts rather than a compile error. The AAP port case below is the one that
 * regressed: gated on the native strategy, the hotspot transport bound nothing.
 */
class WifiLauncherCapabilityTest {

    @Test
    fun `the AAP port is bound on both native transports`() {
        for (strategy in NativeStrategy.entries) {
            assertTrue(
                "Native AA on $strategy must bind 5288 — its credentials name that port",
                WifiLauncherMock.create(WifiLauncherMode.NATIVE, nativeStrategy = strategy)
                    .hasWirelessServer()
            )
        }
    }



}
