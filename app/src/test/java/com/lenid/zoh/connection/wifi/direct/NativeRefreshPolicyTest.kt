package com.lenid.zoh.connection.wifi.direct

import com.lenid.zoh.connection.wifi.direct.NativeRefreshPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRefreshPolicyTest {

    @Test
    fun `a group that is up and ours has its credentials read again`() {
        assertEquals(Action.REDELIVER, NativeRefreshPolicy.decide(groupExists = true, isGroupOwner = true, createInFlightForMs = null))
        // Even mid-create: the group answering is what ends the create.
        assertEquals(Action.REDELIVER, NativeRefreshPolicy.decide(groupExists = true, isGroupOwner = true, createInFlightForMs = 500))
    }

    @Test
    fun `a create that was just asked for is left to answer`() {
        assertEquals(Action.WAIT, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = 0))
        assertEquals(Action.WAIT, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = NativeRefreshPolicy.CREATE_GRACE_MS - 1))
    }

    @Test
    fun `a create that has gone unanswered past the grace is remade`() {
        assertEquals(Action.RECREATE, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = NativeRefreshPolicy.CREATE_GRACE_MS))
    }

    @Test
    fun `a create is in flight from the moment it is claimed until its grace runs out`() {
        // Zero is "nothing was claimed", not "claimed at time zero".
        assertFalse(NativeRefreshPolicy.createInFlight(requestedAtMs = 0L, nowMs = 5_000L))
        assertTrue(NativeRefreshPolicy.createInFlight(requestedAtMs = 1_000L, nowMs = 1_000L))
        assertTrue(NativeRefreshPolicy.createInFlight(requestedAtMs = 1_000L, nowMs = 1_000L + NativeRefreshPolicy.CREATE_GRACE_MS - 1))
        assertFalse(NativeRefreshPolicy.createInFlight(requestedAtMs = 1_000L, nowMs = 1_000L + NativeRefreshPolicy.CREATE_GRACE_MS))
    }

    @Test
    fun `no group and nothing in flight is remade`() {
        assertEquals(Action.RECREATE, NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = null))
    }

    @Test
    fun `a group we are only a client of is not ours to hand out`() {
        assertEquals(Action.RECREATE, NativeRefreshPolicy.decide(groupExists = true, isGroupOwner = false, createInFlightForMs = null))
    }

    // --- an accepted create is left alone for the whole group-info window ---

    @Test
    fun `a create the platform accepted is not remade while it is still being read for`() {
        // Past the grace, which is where the refresh used to remake the group under the reads.
        assertEquals(
            Action.WAIT,
            NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = 16_000L, acceptedCreatePendingForMs = 16_000L)
        )
        assertEquals(
            Action.WAIT,
            NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = null, acceptedCreatePendingForMs = NativeRefreshPolicy.GROUP_INFO_WINDOW_MS - 1)
        )
    }

    @Test
    fun `an accepted create that outlived the group-info window is remade`() {
        assertEquals(
            Action.RECREATE,
            NativeRefreshPolicy.decide(groupExists = false, isGroupOwner = false, createInFlightForMs = null, acceptedCreatePendingForMs = NativeRefreshPolicy.GROUP_INFO_WINDOW_MS)
        )
    }

    @Test
    fun `the group-info window covers the twenty one-second reads`() {
        assertTrue(NativeRefreshPolicy.GROUP_INFO_WINDOW_MS > 20_000L)
        assertTrue(NativeRefreshPolicy.GROUP_INFO_WINDOW_MS > NativeRefreshPolicy.CREATE_GRACE_MS)
    }

    @Test
    fun `the recheck waits for the longer of the two windows`() {
        assertEquals(NativeRefreshPolicy.CREATE_GRACE_MS - 1_000L + 500L, NativeRefreshPolicy.recheckDelayMs(1_000L, null))
        assertEquals(NativeRefreshPolicy.GROUP_INFO_WINDOW_MS - 16_000L + 500L, NativeRefreshPolicy.recheckDelayMs(16_000L, 16_000L))
        assertEquals(NativeRefreshPolicy.GROUP_INFO_WINDOW_MS - 2_000L + 500L, NativeRefreshPolicy.recheckDelayMs(2_000L, 2_000L))
        assertEquals(500L, NativeRefreshPolicy.recheckDelayMs(null, null))
    }
}
