package com.lenid.zoh.connection.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLauncherRestartPolicyTest {

    @Test
    fun `an identical mode that is already running is not started twice`() {
        assertTrue(
            WifiLauncherRestartPolicy.refusesRestart(
                activeIsStarted = true, sameConfiguration = true, force = false
            )
        )
    }

    @Test
    fun `a stopped launcher does not refuse the restart of its own mode`() {
        // The lever a user disconnect leaves behind: stop() keeps the launcher, so this used to be
        // refused and the stack was never armed again.
        assertFalse(
            WifiLauncherRestartPolicy.refusesRestart(
                activeIsStarted = false, sameConfiguration = true, force = false
            )
        )
    }

    @Test
    fun `a different configuration always goes through`() {
        assertFalse(
            WifiLauncherRestartPolicy.refusesRestart(
                activeIsStarted = true, sameConfiguration = false, force = false
            )
        )
        assertFalse(
            WifiLauncherRestartPolicy.refusesRestart(
                activeIsStarted = false, sameConfiguration = false, force = false
            )
        )
    }

    @Test
    fun `force is refused by nothing`() {
        listOf(true, false).forEach { started ->
            listOf(true, false).forEach { same ->
                assertFalse(
                    "started=$started same=$same",
                    WifiLauncherRestartPolicy.refusesRestart(started, same, force = true)
                )
            }
        }
    }
}
