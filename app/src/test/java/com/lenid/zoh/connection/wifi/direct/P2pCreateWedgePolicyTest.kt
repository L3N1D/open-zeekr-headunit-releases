package com.lenid.zoh.connection.wifi.direct

import com.lenid.zoh.connection.wifi.direct.P2pCreateWedgePolicy.Step
import com.lenid.zoh.connection.wifi.direct.P2pCreateWedgePolicy.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ages are the ones #952's log measured: a create accepted at 14:16:42.69 that never formed a
 * group, and the first rung after 120 s that was finally allowed through.
 */
class P2pCreateWedgePolicyTest {

    private fun step(
        reason: Int = P2pCreateWedgePolicy.BUSY,
        age: Long? = 30_000L,
        spent: Boolean = false,
    ) = P2pCreateWedgePolicy.stepAfterBusy(reason, age, spent)

    @Test
    fun `a stuck create of ours is cancelled rather than retried`() {
        assertEquals(Step.CANCEL_FIRST, step(age = 30_000L))
        assertEquals(Step.CANCEL_FIRST, step(age = P2pCreateWedgePolicy.CREATE_STALL_FLOOR_MS))
        assertEquals(Step.CANCEL_FIRST, step(age = P2pCreateWedgePolicy.FRAMEWORK_CREATE_TIMEOUT_MS - 1))
    }

    @Test
    fun `a create that could still land is left alone`() {
        assertEquals(Step.RETRY, step(age = 0L))
        assertEquals(Step.RETRY, step(age = P2pCreateWedgePolicy.CREATE_STALL_FLOOR_MS - 1))
    }

    @Test
    fun `nothing is cancelled once the platform has timed the create out itself`() {
        assertEquals(Step.RETRY, step(age = P2pCreateWedgePolicy.FRAMEWORK_CREATE_TIMEOUT_MS))
        assertEquals(Step.RETRY, step(age = 200_000L))
    }

    @Test
    fun `a BUSY with no create of ours outstanding belongs to somebody else`() {
        assertEquals(Step.RETRY, step(age = null))
    }

    @Test
    fun `only BUSY means the platform is holding a creation`() {
        assertEquals(Step.RETRY, step(reason = 0))
        assertEquals(Step.RETRY, step(reason = 1))
    }

    @Test
    fun `the cancel is spent once per stuck create`() {
        assertEquals(Step.RETRY, step(spent = true))
    }

    @Test
    fun `a refusal is honest unless our own create is still pending`() {
        assertFalse(P2pCreateWedgePolicy.isRefusalHonest(P2pCreateWedgePolicy.BUSY, 30_000L))
        assertTrue(P2pCreateWedgePolicy.isRefusalHonest(P2pCreateWedgePolicy.BUSY, null))
        assertTrue(P2pCreateWedgePolicy.isRefusalHonest(P2pCreateWedgePolicy.BUSY, 200_000L))
        assertTrue(P2pCreateWedgePolicy.isRefusalHonest(0, 30_000L))
    }

    @Test
    fun `a refusal below the stall floor is still a pending create, not a refusal`() {
        assertFalse(P2pCreateWedgePolicy.isRefusalHonest(P2pCreateWedgePolicy.BUSY, 1_000L))
    }

    // --- the group-info loop running out is the stall, with no BUSY needed ---

    @Test
    fun `twenty empty group reads on an accepted create is a stall`() {
        assertEquals(Step.CANCEL_FIRST, P2pCreateWedgePolicy.stepAfterGroupInfoExhausted(21_000L, cancelAlreadySpent = false))
    }

    @Test
    fun `an exhausted loop with no accepted create, or a cancel already spent, cancels nothing`() {
        assertEquals(Step.RETRY, P2pCreateWedgePolicy.stepAfterGroupInfoExhausted(null, cancelAlreadySpent = false))
        assertEquals(Step.RETRY, P2pCreateWedgePolicy.stepAfterGroupInfoExhausted(21_000L, cancelAlreadySpent = true))
        assertEquals(Step.RETRY, P2pCreateWedgePolicy.stepAfterGroupInfoExhausted(P2pCreateWedgePolicy.FRAMEWORK_CREATE_TIMEOUT_MS, cancelAlreadySpent = false))
    }

    // --- what is asked for after a cancel: the same request goes back accepted and hanging ---

    @Test
    fun `each cancel drops something the stuck create asked for`() {
        assertEquals(Variant.NAMED_NO_BAND, P2pCreateWedgePolicy.nextAfterCancel(Variant.BANDED, cancelsSpent = 1))
        assertEquals(Variant.FRAMEWORK_PROFILE, P2pCreateWedgePolicy.nextAfterCancel(Variant.NAMED_NO_BAND, cancelsSpent = 2))
    }

    @Test
    fun `the framework profile is the last thing to try`() {
        assertNull(P2pCreateWedgePolicy.nextAfterCancel(Variant.FRAMEWORK_PROFILE, cancelsSpent = 1))
        assertNull(P2pCreateWedgePolicy.nextAfterCancel(Variant.FRAMEWORK_PROFILE, cancelsSpent = 3))
    }

    @Test
    fun `the cancel budget is one per variant and nothing more`() {
        assertEquals(3, P2pCreateWedgePolicy.MAX_CANCELS_PER_BRING_UP)
        assertNull(P2pCreateWedgePolicy.nextAfterCancel(Variant.BANDED, cancelsSpent = P2pCreateWedgePolicy.MAX_CANCELS_PER_BRING_UP))
        assertNull(P2pCreateWedgePolicy.nextAfterCancel(Variant.NAMED_NO_BAND, cancelsSpent = P2pCreateWedgePolicy.MAX_CANCELS_PER_BRING_UP))
    }

    @Test
    fun `a banded create refused twice still has the profile left after the name`() {
        // The banded rung was stuck, cancelled, then the named rung was stuck and cancelled.
        assertEquals(Variant.FRAMEWORK_PROFILE, P2pCreateWedgePolicy.nextAfterCancel(Variant.NAMED_NO_BAND, cancelsSpent = 2))
        // And the profile itself stuck: that is the end.
        assertNull(P2pCreateWedgePolicy.nextAfterCancel(Variant.FRAMEWORK_PROFILE, cancelsSpent = 3))
    }
}
