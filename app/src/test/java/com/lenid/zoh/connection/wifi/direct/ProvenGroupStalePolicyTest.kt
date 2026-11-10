package com.lenid.zoh.connection.wifi.direct

import com.lenid.zoh.connection.wifi.direct.ProvenGroupStalePolicy.UNANSWERED_POKES_BEFORE_STALE
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeHandoffPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenGroupStalePolicyTest {

    private fun stale(
        hosted: Boolean = true,
        session: Boolean = false,
        handshake: Boolean = false,
        pokes: Int = UNANSWERED_POKES_BEFORE_STALE,
    ) = ProvenGroupStalePolicy.isStale(hosted, session, handshake, pokes)

    @Test
    fun `a proven group the phone stopped answering is stale`() {
        assertTrue(stale())
    }

    @Test
    fun `a group that never carried a session is not this policy's business`() {
        // NativeJoinRecoveryPolicy owns that case; this one must not claim it too.
        assertFalse(stale(hosted = false))
    }

    @Test
    fun `a live session is never stale, however many pokes went unanswered`() {
        assertFalse(stale(session = true, pokes = 100))
    }

    @Test
    fun `an exchange in flight is never stale`() {
        // Recreating here hands the phone an SSID it can no longer join.
        assertFalse(stale(handshake = true, pokes = 100))
    }

    @Test
    fun `the threshold is a floor, not an equality`() {
        assertFalse(stale(pokes = UNANSWERED_POKES_BEFORE_STALE - 1))
        assertTrue(stale(pokes = UNANSWERED_POKES_BEFORE_STALE))
        assertTrue(stale(pokes = UNANSWERED_POKES_BEFORE_STALE + 50))
    }

    @Test
    fun `a phone that has not been poked yet is not stale`() {
        assertFalse(stale(pokes = 0))
    }

    @Test
    fun `this policy is the escape hatch for the rule that keeps a proven group`() {
        // The two are exact opposites on the group that has hosted a session: the handoff policy
        // refuses to re-arm for it, and this one is the single condition that overrides that.
        assertFalse(
            NativeHandoffPolicy.shouldRearmJoinWatchdogAfterClientLeft(
                nativeAaMode = true, groupHasHostedSession = true)
        )
        assertTrue(stale())
    }

    @Test
    fun `the threshold outlasts a phone that is merely slow`() {
        // Three pokes is the interval NativeHandoffPolicy already calls "had its chance".
        assertTrue(UNANSWERED_POKES_BEFORE_STALE > NativeHandoffPolicy.SILENT_POKE_WARN_INTERVAL - 1)
    }
}
