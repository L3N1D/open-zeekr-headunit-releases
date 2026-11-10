package com.lenid.zoh.connection.wifi.modes.nativeaa

import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.DriverPresence
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.Mode
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.PokeHold
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.SwitchGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDriverSelectionPolicyTest {

    @Test
    fun `disabled mode never shows selector`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 0, scopedCount = 0, connectedCount = 0))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 1, scopedCount = 1, connectedCount = 1))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 2, scopedCount = 2, connectedCount = 2))
    }

    @Test
    fun `one phone in scope, or none paired, never shows the selector in any mode`() {
        for (mode in Mode.entries) {
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 0, scopedCount = 0, connectedCount = 0))
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 1, scopedCount = 1, connectedCount = 1))
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 1, scopedCount = 1, connectedCount = 0))
        }
    }

    @Test
    fun `AUTO with one phone and an empty scope asks`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, connectedCount = 0, scopedCount = 0))
    }

    @Test
    fun `AUTO with one connected phone outside the scope still skips the prompt`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, connectedCount = 1, scopedCount = 0))
        assertEquals(
            "AA:BB",
            NativeDriverSelectionPolicy.resolveAutoConnectTarget("", "", listOf("AA:BB"), listOf("AA:BB"), emptyList())
        )
    }

    @Test
    fun `an empty scope names no countdown target for a single phone`() {
        assertNull(NativeDriverSelectionPolicy.resolveAutoConnectTarget("", "AA:BB", emptyList(), listOf("AA:BB"), emptyList()))
    }

    @Test
    fun `ALWAYS with one phone and an empty scope stays silent`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 1, connectedCount = 0, scopedCount = 0))
    }

    @Test
    fun `no paired phone never asks, whatever the scope`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 0, connectedCount = 0, scopedCount = 0))
    }

    @Test
    fun `always mode shows selector whenever 2 or more devices are paired`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, scopedCount = 2, connectedCount = 0))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, scopedCount = 2, connectedCount = 1))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, scopedCount = 2, connectedCount = 2))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 5, scopedCount = 5, connectedCount = 1))
    }

    @Test
    fun `auto mode skips selector if exactly one device is confirmed connected`() {
        // Solo driver entering car with their phone already connected to BT: zero intrusion
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 1))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 4, scopedCount = 4, connectedCount = 1))
    }

    @Test
    fun `auto mode shows selector if 2 or more devices are connected`() {
        // Both drivers enter car together: conflict prompt
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 2))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, scopedCount = 3, connectedCount = 2))
    }

    @Test
    fun `auto mode shows selector if 0 devices are confirmed connected but multiple paired`() {
        // Connection state unknown (e.g. phones not yet connected to BT stack)
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 0))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, scopedCount = 3, connectedCount = 0))
    }

    @Test
    fun `resolveAutoConnectTarget selects single connected device first`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "MAC_PREF",
            lastUsedMac = "MAC_LAST",
            connectedMacs = listOf("MAC_ALONE"),
            pairedMacs = listOf("MAC_PREF", "MAC_LAST", "MAC_ALONE"),
            scopeMacs = listOf("MAC_PREF", "MAC_LAST", "MAC_ALONE")
        )
        assertEquals("MAC_ALONE", target)
    }

    @Test
    fun `resolveAutoConnectTarget prioritizes preferred device when multiple connected`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "MAC_PREF",
            lastUsedMac = "MAC_LAST",
            connectedMacs = listOf("MAC_LAST", "MAC_PREF"),
            pairedMacs = listOf("MAC_PREF", "MAC_LAST", "MAC_OTHER"),
            scopeMacs = listOf("MAC_PREF", "MAC_LAST", "MAC_OTHER")
        )
        assertEquals("MAC_PREF", target)
    }

    @Test
    fun `resolveAutoConnectTarget prioritizes last used device when no preferred device`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "MAC_LAST",
            connectedMacs = listOf("MAC_OTHER", "MAC_LAST"),
            pairedMacs = listOf("MAC_OTHER", "MAC_LAST"),
            scopeMacs = listOf("MAC_OTHER", "MAC_LAST")
        )
        assertEquals("MAC_LAST", target)
    }

    @Test
    fun `fresh install with multiple devices and none connected never auto-selects random paired device`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = emptyList(),
            pairedMacs = listOf("MAC_OLD_PHONE", "MAC_SPEAKER"),
            scopeMacs = listOf("MAC_OLD_PHONE", "MAC_SPEAKER")
        )
        assertNull(target)
    }

    @Test
    fun `fresh install with single connected device auto-selects it without history`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = listOf("MAC_CONNECTED"),
            pairedMacs = listOf("MAC_CONNECTED", "MAC_OTHER"),
            scopeMacs = listOf("MAC_CONNECTED", "MAC_OTHER")
        )
        assertEquals("MAC_CONNECTED", target)
    }

    @Test
    fun `fresh install with single paired device auto-selects it without history`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = emptyList(),
            pairedMacs = listOf("MAC_SOLO"),
            scopeMacs = listOf("MAC_SOLO")
        )
        assertEquals("MAC_SOLO", target)
    }

    @Test
    fun `first start without history and multiple devices shows selector`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 0, hasHistory = false))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, scopedCount = 3, connectedCount = 2, hasHistory = false))
    }

    @Test
    fun `single device never shows selector even without history`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, scopedCount = 1, connectedCount = 1, hasHistory = false))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, scopedCount = 1, connectedCount = 0, hasHistory = false))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 1, hasHistory = false))
    }

    @Test
    fun `resolveAutoConnectTarget returns null if candidate is empty`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "MAC_PREF",
            lastUsedMac = "",
            connectedMacs = emptyList(),
            pairedMacs = emptyList(),
            scopeMacs = emptyList()
        )
        assertNull(target)
    }

    @Test
    fun `sanitizeTimeout clamps values between 3 and 30 seconds`() {
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(-10))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(-1))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(0))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(1))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(3))
        assertEquals(10, NativeDriverSelectionPolicy.sanitizeTimeout(10))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(30))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(31))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(60))
    }

    @Test
    fun `prompt deferral is the countdown plus a fixed grace`() {
        assertEquals(
            10 * 1000L + NativeDriverSelectionPolicy.PROMPT_GRACE_MS,
            NativeDriverSelectionPolicy.promptDeferralMs(10)
        )
        assertEquals(
            30 * 1000L + NativeDriverSelectionPolicy.PROMPT_GRACE_MS,
            NativeDriverSelectionPolicy.promptDeferralMs(30)
        )
    }

    @Test
    fun `prompt deferral clamps its timeout like sanitizeTimeout does`() {
        // A stored timeout of 0 must not mean "defer for the grace alone", nor 3600 mean an hour:
        // this bounds how long a unit with nobody in front of it can go without a wake poke.
        assertEquals(
            NativeDriverSelectionPolicy.promptDeferralMs(NativeDriverSelectionPolicy.MIN_TIMEOUT_SEC),
            NativeDriverSelectionPolicy.promptDeferralMs(0)
        )
        assertEquals(
            NativeDriverSelectionPolicy.promptDeferralMs(NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC),
            NativeDriverSelectionPolicy.promptDeferralMs(3600)
        )
    }

    @Test
    fun `prompt deferral is always bounded and never zero`() {
        for (timeout in -5..40) {
            val deferral = NativeDriverSelectionPolicy.promptDeferralMs(timeout)
            assertTrue(deferral >= NativeDriverSelectionPolicy.MIN_TIMEOUT_SEC * 1000L)
            assertTrue(deferral <= NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC * 1000L + NativeDriverSelectionPolicy.PROMPT_GRACE_MS)
        }
    }

    @Test
    fun `a cancelled prompt refuses for less than a whole prompt window`() {
        // The refusal only has to outlast a poke that was already on the wire when the user
        // cancelled. Longer than the prompt itself and a cancel starts to look permanent again.
        assertTrue(NativeDriverSelectionPolicy.CANCEL_REFUSAL_MS > 0L)
        assertTrue(
            NativeDriverSelectionPolicy.CANCEL_REFUSAL_MS <
                NativeDriverSelectionPolicy.promptDeferralMs(NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC)
        )
    }

    @Test
    fun `no prompt on screen never holds the wake poke`() {
        assertEquals(
            PokeHold.GO,
            NativeDriverSelectionPolicy.pokeHold(
                promptActive = false, targetChosen = false, promptAgeMs = 0L, timeoutSec = 10
            )
        )
    }

    @Test
    fun `a chosen driver ends the hold whatever the clock says`() {
        assertEquals(
            PokeHold.GO,
            NativeDriverSelectionPolicy.pokeHold(
                promptActive = true, targetChosen = true, promptAgeMs = 0L, timeoutSec = 10
            )
        )
    }

    @Test
    fun `a fresh prompt holds the wake poke`() {
        assertEquals(
            PokeHold.HOLD,
            NativeDriverSelectionPolicy.pokeHold(
                promptActive = true, targetChosen = false, promptAgeMs = 1_000L, timeoutSec = 10
            )
        )
    }

    @Test
    fun `an unanswered prompt expires exactly on its own deadline`() {
        val deadline = NativeDriverSelectionPolicy.promptDeferralMs(10)
        assertEquals(
            PokeHold.HOLD,
            NativeDriverSelectionPolicy.pokeHold(true, false, deadline - 1L, 10)
        )
        assertEquals(
            PokeHold.EXPIRED,
            NativeDriverSelectionPolicy.pokeHold(true, false, deadline, 10)
        )
    }

    @Test
    fun `the hold deadline moves with the timeout setting`() {
        // The whole point of the setting. A hold that expires at the same moment for 10 s and 30 s
        // is reading a cadence somewhere else, not this value.
        val age = NativeDriverSelectionPolicy.promptDeferralMs(10)
        assertEquals(PokeHold.EXPIRED, NativeDriverSelectionPolicy.pokeHold(true, false, age, 10))
        assertEquals(PokeHold.HOLD, NativeDriverSelectionPolicy.pokeHold(true, false, age, 30))
    }

    @Test
    fun `a chosen driver is the only phone accepted while the window stands`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("aa:bb", "AA:BB", 0L, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
        assertEquals(
            SwitchGate.WRONG_PHONE,
            NativeDriverSelectionPolicy.switchGate("cc:dd", "AA:BB", 0L, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `an unfinished choice stops refusing once its window passes`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "cc:dd", "AA:BB", NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MS, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the phone a switch moved away from waits while nobody is chosen`() {
        assertEquals(
            SwitchGate.SWITCHED_AWAY,
            NativeDriverSelectionPolicy.switchGate("aa:bb", null, 0L, "AA:BB", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("cc:dd", null, 0L, "AA:BB", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `an abandoned switch heals itself`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", null, 0L, "AA:BB", NativeDriverSelectionPolicy.SWITCH_AWAY_REFUSAL_MS, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `a choice outranks the phone the switch moved away from`() {
        // Picking the same phone again is allowed: the switch is what the choice answers.
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("aa:bb", "AA:BB", 0L, "AA:BB", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `an unreadable remote address is never refused on a guess`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("", "AA:BB", 0L, "CC:DD", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `no switch and no choice accepts everything`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("aa:bb", null, 0L, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the last connected phone always wins over the poke list`() {
        assertEquals(
            "AA:BB",
            NativeDriverSelectionPolicy.lastUsedMac("AA:BB", setOf("CC:DD"))
        )
    }

    @Test
    fun `a poke list naming one phone stands in for a last used driver`() {
        assertEquals(
            "CC:DD",
            NativeDriverSelectionPolicy.lastUsedMac("", setOf("CC:DD"))
        )
    }

    @Test
    fun `a poke list naming several phones names no driver at all`() {
        assertEquals("", NativeDriverSelectionPolicy.lastUsedMac("", setOf("CC:DD", "EE:FF")))
        assertEquals("", NativeDriverSelectionPolicy.lastUsedMac("", emptySet()))
    }

    @Test
    fun `a chosen driver stays exclusive while its wake is still running`() {
        assertTrue(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MS, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false)
        )
        assertTrue(NativeDriverSelectionPolicy.chosenExclusive(90_000L, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false))
    }

    @Test
    fun `a chosen driver stops being exclusive when nothing is waking it`() {
        assertTrue(NativeDriverSelectionPolicy.chosenExclusive(29_999L, wakeActive = false, explicit = true, wakeUnanswered = false, chosenHandshaking = false))
        assertFalse(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MS, wakeActive = false, explicit = true, wakeUnanswered = false, chosenHandshaking = false)
        )
    }

    @Test
    fun `an unreachable choice cannot hold the gate for good`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false)
        )
    }

    @Test
    fun `the wrong phone waits while the explicit pick is woken and has not missed a round`() {
        assertEquals(
            SwitchGate.WRONG_PHONE,
            NativeDriverSelectionPolicy.switchGate(
                "cc:dd", "AA:BB", 60_000L, null, 0L, chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the chosen phone is accepted late in its own wake`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", "AA:BB", 90_000L, null, 0L, chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the wrong phone is let in once the wake budget runs out`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "cc:dd", "AA:BB", NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, null, 0L,
                chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the phone a switch left keeps waiting after the choice stops being exclusive`() {
        assertEquals(
            SwitchGate.SWITCHED_AWAY,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", "CC:DD", 40_000L, "AA:BB", 40_000L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `picking back the phone the switch left accepts it`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", "AA:BB", 1_000L, "AA:BB", 5_000L, chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the wake budget is three rounds`() {
        assertEquals(3, NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS)
    }

    @Test
    fun `a stood-down wake opened nothing, so it spends no round`() {
        assertFalse(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN))
        assertTrue(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.ANSWERED))
        assertTrue(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.DIALLED))
    }

    @Test
    fun `refusals keep the loop alive long enough for the escalation to mature`() {
        // The whole point: three refusals are over in about 30 s, and the escalation needs 90 s.
        var elapsed = 0L
        var passes = 0
        while (NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, 0, elapsed)) {
            passes++
            elapsed += 15_000L
        }
        assertTrue("refusals must outlast ${HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS}ms",
            elapsed > HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS)
        assertTrue(passes > NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS)
    }

    @Test
    fun `dialled rounds still spend the budget, and the ceiling still ends an endless refusal`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.DIALLED, NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS, 0L
            )
        )
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, 0,
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS
            )
        )
    }

    @Test
    fun `an unpaired phone ends the loop rather than being waited out`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.NOT_PAIRED, 0, 0L
            )
        )
    }

    @Test
    fun `a target with a live bluetooth link may take the full screen`() {
        assertTrue(
            NativeDriverSelectionPolicy.connectUiIsImmediate("AA:BB", listOf("CC:DD", "AA:BB"))
        )
    }

    @Test
    fun `the live link is matched case insensitively`() {
        assertTrue(
            NativeDriverSelectionPolicy.connectUiIsImmediate("aa:bb", listOf("AA:BB"))
        )
    }

    @Test
    fun `a target that is not connected defers the full screen`() {
        assertFalse(
            NativeDriverSelectionPolicy.connectUiIsImmediate("AA:BB", listOf("CC:DD"))
        )
    }

    @Test
    fun `nothing connected defers the full screen`() {
        assertFalse(NativeDriverSelectionPolicy.connectUiIsImmediate("AA:BB", emptyList()))
    }

    @Test
    fun `an empty target defers the full screen`() {
        assertFalse(NativeDriverSelectionPolicy.connectUiIsImmediate("", listOf("AA:BB")))
    }

    @Test
    fun `mode id mapping round trip works as expected`() {
        assertEquals(Mode.DISABLED, Mode.fromId(0))
        assertEquals(Mode.AUTO, Mode.fromId(1))
        assertEquals(Mode.ALWAYS, Mode.fromId(2))
        assertEquals(Mode.AUTO, Mode.fromId(-1))
        assertEquals(Mode.AUTO, Mode.fromId(99))
    }

    // --- whose hands-free link is it ---

    private val OUTGOING = "AA:BB:CC:DD:EE:FF"
    private val INCOMING = "11:22:33:44:55:66"

    /** The round-5 failure: the outgoing phone's link stood the incoming phone's wake down. */
    @Test
    fun `during a switch the link belongs to the phone being left`() {
        assertTrue(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, 5_000L))
    }

    @Test
    fun `the phone being left is not another phone`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(OUTGOING, OUTGOING, 5_000L))
    }

    @Test
    fun `a stored address is matched whatever its case`() {
        assertFalse(
            NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(
                OUTGOING.lowercase(), OUTGOING.uppercase(), 5_000L
            )
        )
    }

    @Test
    fun `with no switch underway the link is nobody else's`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, null, 5_000L))
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, "", 5_000L))
    }

    /** An abandoned switch must not lift the guard for good. */
    @Test
    fun `the override expires with the chosen driver's own ceiling`() {
        val ceiling = NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS
        assertTrue(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, ceiling - 1))
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, ceiling))
    }

    /** The stamp is zero until a switch happens, so an unset clock must not read as a fresh switch. */
    @Test
    fun `a nonsense age never lifts the guard`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, -1L))
    }

    @Test
    fun `an empty target is never another phone`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones("", OUTGOING, 5_000L))
    }

    // --- what the selector tells the driver about a phone ---

    @Test
    fun `a phone with a live link reads as connected`() {
        assertEquals(
            DriverPresence.CONNECTED,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, listOf(OUTGOING, INCOMING), true)
        )
    }

    @Test
    fun `a phone that is not in the connected set reads as disconnected`() {
        assertEquals(
            DriverPresence.DISCONNECTED,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, listOf(OUTGOING), true)
        )
    }

    @Test
    fun `presence matches a stored address whatever its case`() {
        assertEquals(
            DriverPresence.CONNECTED,
            NativeDriverSelectionPolicy.presenceOf(INCOMING.lowercase(), listOf(INCOMING.uppercase()), true)
        )
    }

    /**
     * The read is unavailable below API 21 and on adapters that refuse it, and there every phone
     * would otherwise be labelled absent while sitting connected. Say nothing instead.
     */
    @Test
    fun `an unreadable stack reports nothing rather than absence`() {
        assertEquals(
            DriverPresence.UNKNOWN,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, emptyList(), false)
        )
        assertEquals(
            DriverPresence.UNKNOWN,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, listOf(INCOMING), false)
        )
    }

    @Test
    fun `an empty address is never claimed to be present or absent`() {
        assertEquals(DriverPresence.UNKNOWN, NativeDriverSelectionPolicy.presenceOf("", listOf(INCOMING), true))
    }

    /** The row label and the connect UI must never disagree about the same phone. */
    @Test
    fun `presence agrees with the connect UI decision`() {
        for (macs in listOf(emptyList<String>(), listOf(OUTGOING), listOf(INCOMING))) {
            val immediate = NativeDriverSelectionPolicy.connectUiIsImmediate(INCOMING, macs)
            val connected = NativeDriverSelectionPolicy.presenceOf(INCOMING, macs, true) == DriverPresence.CONNECTED
            assertEquals(immediate, connected)
        }
    }

    // --- how often a repeated refusal is said again ---

    @Test
    fun `a refusal just spoken is not repeated`() {
        assertFalse(NativeDriverSelectionPolicy.shouldRestateRefusal(0L))
    }

    @Test
    fun `a refusal is not repeated while it is still fresh`() {
        assertFalse(
            NativeDriverSelectionPolicy.shouldRestateRefusal(NativeDriverSelectionPolicy.GATE_RESTATE_MS - 1)
        )
    }

    @Test
    fun `a refusal is repeated once the gap has passed`() {
        assertTrue(
            NativeDriverSelectionPolicy.shouldRestateRefusal(NativeDriverSelectionPolicy.GATE_RESTATE_MS)
        )
        assertTrue(
            NativeDriverSelectionPolicy.shouldRestateRefusal(NativeDriverSelectionPolicy.GATE_RESTATE_MS * 10)
        )
    }

    /** A clock that runs backwards must not turn the restate into a second flood. */
    @Test
    fun `a negative age never restates`() {
        assertFalse(NativeDriverSelectionPolicy.shouldRestateRefusal(-1L))
        assertFalse(NativeDriverSelectionPolicy.shouldRestateRefusal(Long.MIN_VALUE))
    }

    // A watch that is connected must be scoped out before the counts reach the policy: the
    // caller hands over phones only, and these pin what that buys.

    @Test
    fun `a connected watch scoped out keeps the one connected phone unambiguous`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 1))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, scopedCount = 2, connectedCount = 2))
    }

    @Test
    fun `a connected device outside the phone list never wins over the phone`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = listOf("MAC_WATCH", "MAC_PHONE"),
            pairedMacs = listOf("MAC_PHONE", "MAC_OTHER_PHONE"),
            scopeMacs = listOf("MAC_PHONE", "MAC_OTHER_PHONE")
        )
        assertEquals("MAC_PHONE", target)
    }

    @Test
    fun `only a watch connected never returns the watch`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = listOf("MAC_WATCH"),
            pairedMacs = listOf("MAC_PHONE", "MAC_OTHER_PHONE"),
            scopeMacs = listOf("MAC_PHONE", "MAC_OTHER_PHONE")
        )
        assertNull(target)
    }

    @Test
    fun `a poke list naming only a watch is inert once the paired list is phones`() {
        val lastUsed = NativeDriverSelectionPolicy.lastUsedMac("", setOf("MAC_WATCH"))
        assertEquals("MAC_WATCH", lastUsed)
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = lastUsed,
            connectedMacs = emptyList(),
            pairedMacs = listOf("MAC_PHONE", "MAC_OTHER_PHONE"),
            scopeMacs = listOf("MAC_PHONE", "MAC_OTHER_PHONE")
        )
        assertNull(target)
    }

    private val poco = "MAC_POCO"
    private val moto = "MAC_MOTO"

    private fun scoped(
        preferred: String = "",
        last: String = "",
        connected: List<String> = emptyList(),
        offered: List<String>,
        scope: List<String>
    ) = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
        preferredMac = preferred,
        lastUsedMac = last,
        connectedMacs = connected,
        pairedMacs = offered,
        scopeMacs = scope
    )

    @Test
    fun `autoScope keeps only the offered phones a selected list names, case ignored`() {
        val targets = PokeTargets.Selected(setOf("aa:bb"))
        assertEquals(listOf("AA:BB"), NativeDriverSelectionPolicy.autoScope(listOf("AA:BB", "CC:DD"), targets))
    }

    @Test
    fun `autoScope adds nothing for a selected address that is not offered`() {
        val targets = PokeTargets.Selected(setOf("EE:FF"))
        assertEquals(emptyList<String>(), NativeDriverSelectionPolicy.autoScope(listOf("AA:BB"), targets))
    }

    @Test
    fun `autoScope keeps every offered phone for AllPaired and none for None`() {
        val offered = listOf("AA:BB", "CC:DD")
        assertEquals(offered, NativeDriverSelectionPolicy.autoScope(offered, PokeTargets.AllPaired))
        assertEquals(emptyList<String>(), NativeDriverSelectionPolicy.autoScope(offered, PokeTargets.None))
    }

    @Test
    fun `a last used phone outside the wake list is not the countdown pick`() {
        assertEquals(poco, scoped(last = moto, offered = listOf(poco, moto), scope = listOf(poco)))
    }

    @Test
    fun `a preferred phone outside the scope is ignored`() {
        assertEquals(poco, scoped(preferred = moto, offered = listOf(poco, moto), scope = listOf(poco)))
    }

    @Test
    fun `a connected phone outside the scope still wins`() {
        assertEquals(moto, scoped(connected = listOf(moto), offered = listOf(poco, moto), scope = listOf(poco)))
    }

    @Test
    fun `an empty scope names nobody`() {
        assertNull(scoped(last = moto, offered = listOf(poco, moto), scope = emptyList()))
    }

    @Test
    fun `a last used phone inside a scope of two still wins`() {
        assertEquals(moto, scoped(last = moto, offered = listOf(poco, moto), scope = listOf(poco, moto)))
    }

    @Test
    fun `AUTO skips the selector when one phone is in scope and none is connected`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, scopedCount = 1))
    }

    @Test
    fun `AUTO shows the selector with no countdown target when nothing is in scope`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, scopedCount = 0))
    }

    @Test
    fun `ALWAYS counts every offered phone whatever the scope`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, connectedCount = 0, scopedCount = 1))
    }

    private fun gate(
        remote: String = "cc:dd",
        chosen: String? = "AA:BB",
        ageMs: Long,
        switchedAway: String? = null,
        switchAgeMs: Long = 0L,
        wakeActive: Boolean = true,
        explicit: Boolean,
        unanswered: Boolean,
        handshakePeer: String? = null
    ) = NativeDriverSelectionPolicy.switchGate(
        remote, chosen, ageMs, switchedAway, switchAgeMs, wakeActive, explicit, unanswered, handshakePeer
    )

    @Test
    fun `an automatic pick is never exclusive`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 0L, explicit = false, unanswered = false))
    }

    @Test
    fun `an automatic pick still refuses the phone a switch moved away from`() {
        assertEquals(
            SwitchGate.SWITCHED_AWAY,
            gate(remote = "aa:bb", chosen = "CC:DD", ageMs = 10_000L, switchedAway = "AA:BB",
                switchAgeMs = 10_000L, explicit = false, unanswered = false)
        )
    }

    @Test
    fun `an explicit pick that has not missed a round keeps the lock while its wake runs`() {
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 90_000L, explicit = true, unanswered = false))
    }

    @Test
    fun `an explicit pick that missed a round keeps the floor`() {
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 20_000L, explicit = true, unanswered = true))
    }

    @Test
    fun `an explicit pick that missed a round lets others in after the floor`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 31_000L, explicit = true, unanswered = true))
    }

    @Test
    fun `an unanswered explicit pick is not exclusive past the floor`() {
        assertFalse(NativeDriverSelectionPolicy.chosenExclusive(31_000L, wakeActive = true, explicit = true, wakeUnanswered = true, chosenHandshaking = false))
    }

    @Test
    fun `the ceiling ends an explicit lock whose wake is active and answered`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false
            )
        )
    }

    @Test
    fun `pickLapsedAfterRound truth table`() {
        val p = NativeDriverSelectionPolicy
        assertTrue(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, answeredSincePick = false, lapsed = false))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, answeredSincePick = true, lapsed = false))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, answeredSincePick = false, lapsed = true))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, answeredSincePick = false, lapsed = false))
        assertTrue(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, answeredSincePick = false, lapsed = true))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, answeredSincePick = false, lapsed = false))
        assertTrue(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.NOT_PAIRED, answeredSincePick = false, lapsed = true))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.NOT_PAIRED, answeredSincePick = false, lapsed = false))
    }

    @Test
    fun `a lapsed pick that answers a later round holds the gate again`() {
        val p = NativeDriverSelectionPolicy
        val lapsed = p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, false, p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, false, false))
        assertEquals(
            SwitchGate.WRONG_PHONE,
            p.switchGate("cc:dd", "AA:BB", 60_000L, null, 0L, chosenWakeActive = true, chosenExplicit = true,
                chosenWakeUnanswered = lapsed, handshakePeerMac = null)
        )
    }

    @Test
    fun `an answered pick does not lapse again on a later dialled round`() {
        val p = NativeDriverSelectionPolicy
        val afterAnswer = p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, false, false)
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, answeredSincePick = true, lapsed = afterAnswer))
    }

    @Test
    fun `a lapsed pick still holds the gate while its own handshake runs`() {
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 60_000L, explicit = true, unanswered = true, handshakePeer = "aa:bb"))
    }

    @Test
    fun `another phone's handshake does not hold a lapsed pick`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 60_000L, explicit = true, unanswered = true, handshakePeer = "ee:ff"))
    }

    @Test
    fun `a lapsed pick with no handshake lets others in after the floor`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 60_000L, explicit = true, unanswered = true, handshakePeer = null))
    }

    @Test
    fun `the chosen phone's handshake does not outlast the ceiling`() {
        assertEquals(
            SwitchGate.ACCEPT,
            gate(ageMs = NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, explicit = true, unanswered = true, handshakePeer = "AA:BB")
        )
    }

    @Test
    fun `an automatic pick gets no lock from its handshake`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 10_000L, explicit = false, unanswered = false, handshakePeer = "AA:BB"))
    }

    @Test
    fun `chosenExclusive holds for the chosen phone's handshake`() {
        assertTrue(
            NativeDriverSelectionPolicy.chosenExclusive(
                60_000L, wakeActive = false, explicit = true, wakeUnanswered = true, chosenHandshaking = true)
        )
    }
}
