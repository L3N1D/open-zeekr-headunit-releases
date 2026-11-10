package com.lenid.zoh.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartOfferPolicyTest {

    private val phone = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `one phone that has never been asked about is offered`() {
        assertTrue(
            AutoStartOfferPolicy.offers(
                phonesPaired = 1, connectedMac = phone,
                answeredMacs = emptySet(), autoStartConfigured = false
            )
        )
    }

    @Test
    fun `a phone already answered for is not asked again`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 1, connectedMac = phone,
                answeredMacs = setOf(phone), autoStartConfigured = false
            )
        )
    }

    @Test
    fun `the answered list is matched without case, which is how addresses come back`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 1, connectedMac = phone,
                answeredMacs = setOf(phone.lowercase()), autoStartConfigured = false
            )
        )
    }

    @Test
    fun `nothing is offered when auto-start is already set up`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 1, connectedMac = phone,
                answeredMacs = emptySet(), autoStartConfigured = true
            )
        )
    }

    /**
     * The device in the list was chosen by hand, and clearing it was reported as the setting
     * refusing to save. Nothing this policy answers ever deletes it.
     */
    @Test
    fun `two paired phones leave a stored trigger alone`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 2, connectedMac = phone,
                answeredMacs = emptySet(), autoStartConfigured = true
            )
        )
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 3, connectedMac = phone,
                answeredMacs = setOf(phone), autoStartConfigured = true
            )
        )
    }

    @Test
    fun `two paired phones are not asked about`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 2, connectedMac = phone,
                answeredMacs = emptySet(), autoStartConfigured = false
            )
        )
    }

    @Test
    fun `a phone that cannot be named is never offered`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 1, connectedMac = "",
                answeredMacs = emptySet(), autoStartConfigured = false
            )
        )
    }

    @Test
    fun `no paired phones offers nothing`() {
        assertFalse(
            AutoStartOfferPolicy.offers(
                phonesPaired = 0, connectedMac = phone,
                answeredMacs = emptySet(), autoStartConfigured = false
            )
        )
    }

    @Test
    fun `the offer answers itself after twenty seconds`() {
        assertEquals(20_000L, AutoStartOfferPolicy.OFFER_TIMEOUT_MS)
    }
}
