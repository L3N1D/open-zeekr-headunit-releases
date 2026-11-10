package com.lenid.zoh.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeTargetPolicyTest {

    private val phone = setOf("A0:46:5A:97:E4:95")
    private val car = setOf("00:1E:B8:12:34:56")

    /**
     * The narrowing a user asks for by turning the opt-in off: wake this phone or nothing. Widening
     * stays the default, because the poke is the only thing that starts Android Auto on some units.
     */
    @Test
    fun `no target and no opt-in pokes nothing`() {
        assertEquals(
            PokeTargets.None,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = false)
        )
    }

    /** The default, and what an empty list used to do implicitly. */
    @Test
    fun `no target with the opt-in on pokes every paired device`() {
        assertEquals(
            PokeTargets.AllPaired,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = true)
        )
    }

    /** A chosen target is used as chosen, and the opt-in never widens it. */
    @Test
    fun `a chosen target is never widened`() {
        assertEquals(
            PokeTargets.Selected(phone),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = true)
        )
        assertEquals(
            PokeTargets.Selected(phone),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = false)
        )
    }

    /**
     * The field failure: a completed handshake wrote its peer into the list that also gated
     * Bluetooth auto-start, so clearing auto-start undid itself. It fills the poke target only,
     * and only when there is none.
     */
    @Test
    fun `a handshaked device is adopted only when nothing is chosen`() {
        assertTrue(PokeTargetPolicy.adoptsHandshakedDevice(emptySet()))
        assertFalse(PokeTargetPolicy.adoptsHandshakedDevice(phone))
    }

    /**
     * The seeded case: the list is copied from the auto-start trigger list, which may name the
     * car's own Bluetooth. A list of only that is a list of nothing, so the opt-in decides.
     */
    @Test
    fun `a selection of only non-phones falls back to every paired device when opted in`() {
        assertEquals(
            PokeTargets.AllPaired,
            PokeTargetPolicy.targets(selected = car, allPairedOptIn = true, notPhones = car)
        )
    }

    @Test
    fun `a selection of only non-phones pokes nothing when opted out`() {
        assertEquals(
            PokeTargets.None,
            PokeTargetPolicy.targets(selected = car, allPairedOptIn = false, notPhones = car)
        )
    }

    @Test
    fun `a mixed selection offers the phones and reports the rest dropped`() {
        assertEquals(
            PokeTargets.Selected(phone, dropped = car),
            PokeTargetPolicy.targets(selected = phone + car, allPairedOptIn = false, notPhones = car)
        )
    }

    @Test
    fun `adoption treats a selection of only non-phones as empty`() {
        assertTrue(PokeTargetPolicy.adoptsHandshakedDevice(car, notPhones = car))
        assertFalse(PokeTargetPolicy.adoptsHandshakedDevice(phone + car, notPhones = car))
    }
}
