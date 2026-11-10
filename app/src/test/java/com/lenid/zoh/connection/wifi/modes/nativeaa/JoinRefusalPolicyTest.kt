package com.lenid.zoh.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinRefusalPolicyTest {

    private val normal = 5_000L

    private fun delay(refusals: Int) = JoinRefusalPolicy.retryDelayMs(refusals, normal)

    @Test
    fun `nothing refused yet leaves the ordinary cadence alone`() {
        assertEquals(normal, delay(0))
    }

    @Test
    fun `one refusal still gets a prompt retry`() {
        assertEquals(normal, delay(JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE))
    }

    @Test
    fun `the second refusal widens the gap`() {
        assertEquals(JoinRefusalPolicy.WIDENED_DELAY_MS, delay(2))
    }

    @Test
    fun `a run of refusals reaches the ceiling and stays there`() {
        assertEquals(JoinRefusalPolicy.CEILING_DELAY_MS, delay(JoinRefusalPolicy.REFUSALS_BEFORE_CEILING))
        assertEquals(JoinRefusalPolicy.CEILING_DELAY_MS, delay(50))
        assertEquals(JoinRefusalPolicy.CEILING_DELAY_MS, delay(5_000))
    }

    @Test
    fun `the delay never shrinks as refusals mount`() {
        var previous = 0L
        for (refusals in 0..12) {
            val current = delay(refusals)
            assertTrue("delay shrank at $refusals", current >= previous)
            previous = current
        }
    }

    @Test
    fun `a caller whose ordinary cadence is already slower keeps it`() {
        val slow = JoinRefusalPolicy.CEILING_DELAY_MS * 2
        assertEquals(slow, JoinRefusalPolicy.retryDelayMs(0, slow))
        assertEquals(slow, JoinRefusalPolicy.retryDelayMs(2, slow))
        assertEquals(slow, JoinRefusalPolicy.retryDelayMs(99, slow))
    }

    @Test
    fun `the measured loop is slowed by more than an order of magnitude`() {
        // Measured on a K706 with the phone's hotspot up: attempts 6.83s apart, so ten in the first
        // minute. The handshake itself costs about 1.8s of each gap.
        val handshakeMs = 1_800L
        fun attemptsInFirst(windowMs: Long): Int {
            var elapsed = 0L
            var attempts = 0
            while (elapsed < windowMs) {
                attempts++
                elapsed += handshakeMs + delay(attempts)
            }
            return attempts
        }
        assertTrue(attemptsInFirst(60_000L) <= 4)
        assertTrue(attemptsInFirst(300_000L) <= 6)
    }

    @Test
    fun `only the widening refusal announces itself`() {
        assertFalse(JoinRefusalPolicy.isFirstWidening(0))
        assertFalse(JoinRefusalPolicy.isFirstWidening(JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE))
        assertTrue(JoinRefusalPolicy.isFirstWidening(JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE + 1))
        assertFalse(JoinRefusalPolicy.isFirstWidening(JoinRefusalPolicy.REFUSALS_AT_NORMAL_CADENCE + 2))
        assertFalse(JoinRefusalPolicy.isFirstWidening(20))
    }

    private fun remaining(refusals: Int, refusedAt: Long, now: Long) =
        JoinRefusalPolicy.remainingDelayMs(refusals, normal, refusedAt, now)

    @Test
    fun `a wake round with no refusal behind it waits for nothing`() {
        for (refusals in 0..12) {
            assertEquals("waited at $refusals", 0L, remaining(refusals, 0L, 900_000L))
            assertEquals("waited at $refusals", 0L, remaining(refusals, -1L, 900_000L))
        }
    }

    @Test
    fun `a round starting inside the gap waits out what is left of it`() {
        assertEquals(
            JoinRefusalPolicy.WIDENED_DELAY_MS - 4_000L,
            remaining(2, 10_000L, 14_000L)
        )
        assertEquals(
            JoinRefusalPolicy.CEILING_DELAY_MS - 4_000L,
            remaining(JoinRefusalPolicy.REFUSALS_BEFORE_CEILING, 10_000L, 14_000L)
        )
    }

    @Test
    fun `a gap already served waits for nothing rather than going negative`() {
        assertEquals(0L, remaining(2, 10_000L, 10_000L + JoinRefusalPolicy.WIDENED_DELAY_MS))
        assertEquals(0L, remaining(9, 10_000L, 900_000L))
    }

    @Test
    fun `a stamp the clock has moved behind is capped at the gap itself`() {
        // elapsedRealtime is read from several coroutines and survives a re-arm, so a reading that
        // lands before the stamp must not turn into an unbounded wait.
        assertEquals(JoinRefusalPolicy.WIDENED_DELAY_MS, remaining(2, 900_000L, 0L))
    }

    @Test
    fun `what is left is never more than the gap the same count asks for`() {
        for (refusals in 0..12) {
            val gap = delay(refusals)
            for (age in listOf(0L, 1L, 7_000L, 29_999L, 200_000L)) {
                val left = remaining(refusals, 10_000L, 10_000L + age)
                assertTrue("over the gap at $refusals/$age", left <= gap)
                assertTrue("under zero at $refusals/$age", left >= 0L)
            }
        }
    }
}
