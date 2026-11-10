package com.lenid.zoh.connection.wifi.modes.nativeaa

import com.lenid.zoh.connection.wifi.modes.nativeaa.AaListenerRecoveryPolicy.Exit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AaListenerRecoveryPolicyTest {

    @Test
    fun `a handoff close is not a failure`() {
        assertEquals(Exit.HANDOFF, AaListenerRecoveryPolicy.exitKind(closedForSession = true, running = true))
    }

    @Test
    fun `a stopped manager is not a failure`() {
        assertEquals(Exit.STOPPED, AaListenerRecoveryPolicy.exitKind(closedForSession = false, running = false))
    }

    @Test
    fun `a running manager whose loop ended lost its listener`() {
        assertEquals(Exit.FAILED, AaListenerRecoveryPolicy.exitKind(closedForSession = false, running = true))
    }

    @Test
    fun `a deliberate close outranks the stop`() {
        assertEquals(Exit.HANDOFF, AaListenerRecoveryPolicy.exitKind(closedForSession = true, running = false))
    }

    @Test
    fun `a radio that is off is never reopened onto`() {
        assertFalse(AaListenerRecoveryPolicy.mayReopen(adapterEnabled = false, attemptsSoFar = 0))
    }

    @Test
    fun `reopening is bounded`() {
        assertTrue(AaListenerRecoveryPolicy.mayReopen(true, AaListenerRecoveryPolicy.MAX_REOPEN_ATTEMPTS - 1))
        assertFalse(AaListenerRecoveryPolicy.mayReopen(true, AaListenerRecoveryPolicy.MAX_REOPEN_ATTEMPTS))
    }
}
