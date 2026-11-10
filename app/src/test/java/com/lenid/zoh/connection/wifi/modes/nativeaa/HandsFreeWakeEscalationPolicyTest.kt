package com.lenid.zoh.connection.wifi.modes.nativeaa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeWakeEscalationPolicyTest {

    private val phoneMac = "EC:7C:B6:21:9C:D9"
    private val standDownAt = 1_000L
    private val ripe = standDownAt + HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS

    private fun escalate(
        unitAllowsWake: Boolean = true,
        reason: BluetoothWakePolicy.WakeReason = BluetoothWakePolicy.WakeReason.TARGET_CONNECTED,
        phoneEverOpenedAaChannel: Boolean = true,
        sessionInProgress: Boolean = false,
        standDownSinceMs: Long = standDownAt,
        lastEscalationMs: Long = 0L,
        escalationsUsed: Int = 0,
        now: Long = ripe,
    ) = HandsFreeWakeEscalationPolicy.shouldEscalate(
        unitAllowsWake, reason, phoneEverOpenedAaChannel, sessionInProgress,
        standDownSinceMs, lastEscalationMs, escalationsUsed, now
    )

    @Test
    fun `a stand-down that has run its course wakes the phone anyway`() {
        assertTrue(escalate())
    }

    @Test
    fun `a unit measured to lose its hands-free link never escalates`() {
        assertFalse(escalate(unitAllowsWake = false))
    }

    @Test
    fun `a stand-down younger than the wait is left alone`() {
        assertFalse(escalate(now = ripe - 1))
    }

    @Test
    fun `a phone that has never run Android Auto here is never disturbed`() {
        assertFalse(escalate(phoneEverOpenedAaChannel = false))
    }

    @Test
    fun `an unreadable target link is a guess, and a guess never drops a call`() {
        assertFalse(escalate(reason = BluetoothWakePolicy.WakeReason.TARGET_UNREADABLE))
    }

    @Test
    fun `a reason that was never a refusal cannot escalate`() {
        for (reason in BluetoothWakePolicy.WakeReason.entries) {
            if (reason == BluetoothWakePolicy.WakeReason.TARGET_CONNECTED) continue
            assertFalse(reason.name, escalate(reason = reason))
        }
    }

    @Test
    fun `nothing touches the radio while a session is in progress`() {
        assertFalse(escalate(sessionInProgress = true))
    }

    @Test
    fun `a stand-down that never began has nothing to escalate`() {
        assertFalse(escalate(standDownSinceMs = 0L))
    }

    @Test
    fun `the budget is spent after the last escalation`() {
        assertFalse(escalate(escalationsUsed = HandsFreeWakeEscalationPolicy.MAX_ESCALATED_WAKES))
    }

    @Test
    fun `the cooldown holds a second escalation back`() {
        val justEscalated = ripe
        assertFalse(
            escalate(
                lastEscalationMs = justEscalated,
                escalationsUsed = 1,
                now = justEscalated + HandsFreeWakeEscalationPolicy.ESCALATION_COOLDOWN_MS - 1,
            )
        )
    }

    @Test
    fun `the second escalation goes out once the cooldown has run`() {
        val justEscalated = ripe
        assertTrue(
            escalate(
                lastEscalationMs = justEscalated,
                escalationsUsed = 1,
                now = justEscalated + HandsFreeWakeEscalationPolicy.ESCALATION_COOLDOWN_MS,
            )
        )
    }

    @Test
    fun `the wait outlasts the phone's own retrigger cadence`() {
        // The phone re-triggers on its own about every 45 s where anything is going to, so an
        // ordinary reconnect must never be disturbed.
        assertTrue(HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS > 45_000L)
    }

    private fun ranAaHere(
        acceptedThisProcess: Boolean = false,
        targetMac: String? = phoneMac,
        lastConnectedNativeMac: String = phoneMac,
    ) = HandsFreeWakeEscalationPolicy.pairingHasRunAaHere(
        acceptedThisProcess, targetMac, lastConnectedNativeMac
    )

    @Test
    fun `an accept in this process needs no stored MAC`() {
        assertTrue(ranAaHere(acceptedThisProcess = true, lastConnectedNativeMac = ""))
    }

    @Test
    fun `the stored MAC carries the fact across a reboot`() {
        // The case this exists for: the radio auto-connects hands-free at boot, so the guard stands
        // every poke down before any accept can set the in-process flag.
        assertTrue(ranAaHere())
    }

    @Test
    fun `the stored MAC is matched whatever its case`() {
        assertTrue(ranAaHere(targetMac = phoneMac.lowercase()))
    }

    @Test
    fun `another phone's stored MAC does not vouch for this one`() {
        assertFalse(ranAaHere(lastConnectedNativeMac = "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `a unit that has never handshaked vouches for nobody`() {
        assertFalse(ranAaHere(lastConnectedNativeMac = ""))
    }

    @Test
    fun `an unreadable address is not a match`() {
        assertFalse(ranAaHere(targetMac = null))
        assertFalse(ranAaHere(targetMac = "  "))
    }

    @Test
    fun `a stored MAC does not lift a measured failure`() {
        assertFalse(escalate(unitAllowsWake = false, phoneEverOpenedAaChannel = ranAaHere()))
    }

    @Test
    fun `an arming that never escalated is never stood down`() {
        // The ordinary poke loop is the only thing that works on most units, so it keeps running
        // however long the phone ignores it. Only a wake this unit forced buys the stand-down.
        assertFalse(HandsFreeWakeEscalationPolicy.escalationSpent(escalationsUsed = 0, pokesSinceLastAccept = 99))
    }

    @Test
    fun `an escalated wake that buys nothing gives the link back`() {
        assertFalse(HandsFreeWakeEscalationPolicy.escalationSpent(
            escalationsUsed = 1,
            pokesSinceLastAccept = HandsFreeWakeEscalationPolicy.POKES_AFTER_ESCALATION - 1,
        ))
        assertTrue(HandsFreeWakeEscalationPolicy.escalationSpent(
            escalationsUsed = 1,
            pokesSinceLastAccept = HandsFreeWakeEscalationPolicy.POKES_AFTER_ESCALATION,
        ))
    }

    @Test
    fun `the grace outlasts the reconnect a reporter measured`() {
        // 66 s from poke to accept on #992's unit, against a loop that pokes about every 30 s.
        assertTrue(HandsFreeWakeEscalationPolicy.POKES_AFTER_ESCALATION >= 3)
    }

    @Test
    fun `a phone that answers lifts the stand-down`() {
        assertFalse(HandsFreeWakeEscalationPolicy.escalationSpent(escalationsUsed = 2, pokesSinceLastAccept = 0))
    }
}
