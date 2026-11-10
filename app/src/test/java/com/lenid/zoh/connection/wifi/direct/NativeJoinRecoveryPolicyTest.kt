package com.lenid.zoh.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeJoinRecoveryPolicyTest {

    private val max = 4

    @Test
    fun `a phone that has never opened the bluetooth channel leaves the group alone`() {
        assertEquals(
            NativeJoinRecoveryPolicy.Step.HOLD_PHONE_NEVER_DIALLED,
            NativeJoinRecoveryPolicy.step(phoneDialledThisArming = false, recreateCount = 0, maxRecreates = max)
        )
    }

    @Test
    fun `never having dialled outranks a spent budget, so the give-up line is not claimed either`() {
        assertEquals(
            NativeJoinRecoveryPolicy.Step.HOLD_PHONE_NEVER_DIALLED,
            NativeJoinRecoveryPolicy.step(phoneDialledThisArming = false, recreateCount = max, maxRecreates = max)
        )
    }

    @Test
    fun `a phone that has reached us once is a join failure worth recreating for`() {
        assertEquals(
            NativeJoinRecoveryPolicy.Step.RECREATE,
            NativeJoinRecoveryPolicy.step(phoneDialledThisArming = true, recreateCount = 0, maxRecreates = max)
        )
    }

    @Test
    fun `the last recreate in the budget is still taken`() {
        assertEquals(
            NativeJoinRecoveryPolicy.Step.RECREATE,
            NativeJoinRecoveryPolicy.step(phoneDialledThisArming = true, recreateCount = max - 1, maxRecreates = max)
        )
    }

    @Test
    fun `the budget is spent at the cap, not past it`() {
        assertEquals(
            NativeJoinRecoveryPolicy.Step.GIVE_UP,
            NativeJoinRecoveryPolicy.step(phoneDialledThisArming = true, recreateCount = max, maxRecreates = max)
        )
        assertEquals(
            NativeJoinRecoveryPolicy.Step.GIVE_UP,
            NativeJoinRecoveryPolicy.step(phoneDialledThisArming = true, recreateCount = max + 1, maxRecreates = max)
        )
    }

    @Test
    fun `the hold ends the moment the phone dials, on the same counts`() {
        for (count in 0..max) {
            val held = NativeJoinRecoveryPolicy.step(false, count, max)
            val dialled = NativeJoinRecoveryPolicy.step(true, count, max)
            assertEquals(NativeJoinRecoveryPolicy.Step.HOLD_PHONE_NEVER_DIALLED, held)
            assertEquals(held == dialled, false)
        }
    }
}
