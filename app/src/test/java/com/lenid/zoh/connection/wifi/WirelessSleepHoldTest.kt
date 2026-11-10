package com.lenid.zoh.connection.wifi

import com.lenid.zoh.app.BtAutoStartRearmPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WirelessSleepHoldTest {

    @Before
    fun setUp() = WirelessSleepHold.clear()

    @After
    fun tearDown() = WirelessSleepHold.clear()

    @Test
    fun `a dark screen is asleep`() = assertTrue(WirelessSleepHold.isAsleep(false))

    @Test
    fun `an unreadable screen is awake`() = assertFalse(WirelessSleepHold.isAsleep(null))

    @Test
    fun `a lit screen is awake`() = assertFalse(WirelessSleepHold.isAsleep(true))

    @Test
    fun `an automatic bring-up is refused while asleep`() =
        assertTrue(WirelessSleepHold.refusesBringUp(true, false))

    @Test
    fun `the user lifts the hold`() =
        assertFalse(WirelessSleepHold.refusesBringUp(true, true))

    @Test
    fun `nothing is refused while awake`() =
        assertFalse(WirelessSleepHold.refusesBringUp(false, false))

    @Test
    fun `a formed session drops a forced replay`() =
        assertNull(WirelessSleepHold.replayForce(true, connected = true, connecting = false))

    @Test
    fun `a formed session drops an unforced replay`() =
        assertNull(WirelessSleepHold.replayForce(false, connected = true, connecting = false))

    @Test
    fun `an attempt in flight replays a forced hold unforced`() =
        assertEquals(false, WirelessSleepHold.replayForce(true, connected = false, connecting = true))

    @Test
    fun `an attempt in flight replays an unforced hold unforced`() =
        assertEquals(false, WirelessSleepHold.replayForce(false, connected = false, connecting = true))

    @Test
    fun `with no session the held force replays`() =
        assertEquals(true, WirelessSleepHold.replayForce(true, connected = false, connecting = false))

    @Test
    fun `nothing held replays nothing`() =
        assertNull(WirelessSleepHold.replayForce(null, connected = false, connecting = true))

    @Test
    fun `a forced hold waits out an attempt in flight`() =
        assertTrue(WirelessSleepHold.waitsForAttempt(true, connected = false, connecting = true))

    @Test
    fun `an unforced hold does not wait for an attempt`() =
        assertFalse(WirelessSleepHold.waitsForAttempt(false, connected = false, connecting = true))

    @Test
    fun `a forced hold does not wait with no attempt`() =
        assertFalse(WirelessSleepHold.waitsForAttempt(true, connected = false, connecting = false))

    @Test
    fun `a formed session ends the wait`() =
        assertFalse(WirelessSleepHold.waitsForAttempt(true, connected = true, connecting = false))

    @Test
    fun `take with nothing held is null`() = assertNull(WirelessSleepHold.take())

    @Test
    fun `a hold is taken once`() {
        WirelessSleepHold.hold(false)
        assertEquals(false, WirelessSleepHold.take())
        assertNull(WirelessSleepHold.take())
    }

    @Test
    fun `force is sticky across holds`() {
        WirelessSleepHold.hold(false)
        WirelessSleepHold.hold(true)
        assertEquals(true, WirelessSleepHold.take())
    }

    @Test
    fun `only the first hold since a take reports new`() {
        assertTrue(WirelessSleepHold.hold(false))
        assertFalse(WirelessSleepHold.hold(true))
        WirelessSleepHold.take()
        assertTrue(WirelessSleepHold.hold(false))
    }

    @Test
    fun `clear drops a hold`() {
        WirelessSleepHold.hold(true)
        WirelessSleepHold.clear()
        assertNull(WirelessSleepHold.take())
    }

    @Test
    fun `the radio is not switched on while asleep`() =
        assertTrue(WirelessSleepHold.refusesRadioEnable(true, startedByUser = false))

    @Test
    fun `the radio is switched on while awake`() =
        assertFalse(WirelessSleepHold.refusesRadioEnable(false, startedByUser = false))

    @Test
    fun `the user's bring-up switches the radio on while asleep`() =
        assertFalse(WirelessSleepHold.refusesRadioEnable(true, startedByUser = true))

    @Test
    fun `no group is recreated while asleep`() =
        assertTrue(WirelessSleepHold.refusesGroupRecreate(true, startedByUser = false))

    @Test
    fun `a group is recreated while awake`() =
        assertFalse(WirelessSleepHold.refusesGroupRecreate(false, startedByUser = false))

    @Test
    fun `the user's bring-up recreates its group while asleep`() =
        assertFalse(WirelessSleepHold.refusesGroupRecreate(true, startedByUser = true))

    @Test
    fun `a start records who asked and drops the hold`() {
        WirelessSleepHold.hold(true)
        WirelessSleepHold.started(userRequested = true)
        assertTrue(WirelessSleepHold.startedByUser)
        assertNull(WirelessSleepHold.take())
        WirelessSleepHold.started(userRequested = false)
        assertFalse(WirelessSleepHold.startedByUser)
    }

    @Test
    fun `clear forgets the user's start`() {
        WirelessSleepHold.started(userRequested = true)
        WirelessSleepHold.clear()
        assertFalse(WirelessSleepHold.startedByUser)
    }

    @Test
    fun `a radio hold forces the replay`() {
        WirelessSleepHold.hold(false)
        WirelessSleepHold.hold(true)
        assertEquals(true, WirelessSleepHold.take())
    }

    @Test
    fun `an unforced replay is never vetoed`() =
        assertNull(WirelessSleepHold.replayVeto(force = false, networkComingUp = true, attemptInFlight = true))

    @Test
    fun `a forced replay with nothing under way goes ahead`() =
        assertNull(WirelessSleepHold.replayVeto(force = true, networkComingUp = false, attemptInFlight = false))

    @Test
    fun `a forced replay with nothing readable goes ahead`() =
        assertNull(WirelessSleepHold.replayVeto(force = true, networkComingUp = null, attemptInFlight = null))

    @Test
    fun `a forced replay is vetoed while the network is coming up`() = assertEquals(
        "the network has been asked for and has not answered yet",
        WirelessSleepHold.replayVeto(force = true, networkComingUp = true, attemptInFlight = false)
    )

    @Test
    fun `a forced replay is vetoed while an attempt is in flight`() = assertEquals(
        "a handshake attempt is already in flight",
        WirelessSleepHold.replayVeto(force = true, networkComingUp = false, attemptInFlight = true)
    )

    @Test
    fun `the replay veto matches the Bluetooth auto-start's`() {
        val values = listOf(true, false, null)
        for (n in values) for (a in values) {
            assertEquals(
                "networkComingUp=$n attemptInFlight=$a",
                BtAutoStartRearmPolicy.vetoReason(
                    sessionUp = false, handshakeActive = null, attemptInFlight = a,
                    groupUp = null, networkComingUp = n
                ),
                WirelessSleepHold.replayVeto(force = true, networkComingUp = n, attemptInFlight = a)
            )
        }
    }

    @Test
    fun `a forced replay keeps its force with no start since the take`() =
        assertTrue(WirelessSleepHold.replayKeepsForce(true, 3, 3))

    @Test
    fun `a start since the take drops the replay's force`() =
        assertFalse(WirelessSleepHold.replayKeepsForce(true, 3, 4))

    @Test
    fun `an unforced replay stays unforced after a start`() =
        assertFalse(WirelessSleepHold.replayKeepsForce(false, 3, 4))

    @Test
    fun `take records the starts so far`() {
        WirelessSleepHold.started(false)
        WirelessSleepHold.hold(true)
        WirelessSleepHold.take()
        assertEquals(WirelessSleepHold.starts, WirelessSleepHold.startsAtTake)
    }

    @Test
    fun `a take with nothing held keeps the baseline`() {
        WirelessSleepHold.hold(true)
        WirelessSleepHold.take()
        val baseline = WirelessSleepHold.startsAtTake
        WirelessSleepHold.started(false)
        assertNull(WirelessSleepHold.take())
        assertEquals(baseline, WirelessSleepHold.startsAtTake)
        assertFalse(
            WirelessSleepHold.replayKeepsForce(true, WirelessSleepHold.startsAtTake, WirelessSleepHold.starts)
        )
    }

    @Test
    fun `a start after the take is seen by the replay`() {
        WirelessSleepHold.hold(true)
        WirelessSleepHold.take()
        WirelessSleepHold.started(false)
        assertFalse(
            WirelessSleepHold.replayKeepsForce(true, WirelessSleepHold.startsAtTake, WirelessSleepHold.starts)
        )
    }

    @Test
    fun `a stop ends the user's start`() {
        WirelessSleepHold.started(true)
        WirelessSleepHold.stopped()
        assertFalse(WirelessSleepHold.startedByUser)
    }

    @Test
    fun `a stop keeps a held bring-up`() {
        WirelessSleepHold.hold(true)
        WirelessSleepHold.stopped()
        assertEquals(true, WirelessSleepHold.take())
    }
}
