package com.lenid.zoh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStarvationPolicyTest {

    private fun streakAfter(vararg sessions: Pair<Boolean, Boolean>): Int =
        sessions.fold(0) { streak, (reachedHandshake, rendered) ->
            VideoStarvationPolicy.nextStreak(streak, reachedHandshake, rendered)
        }

    private val starved = true to false
    private val healthy = true to true
    private val neverConnected = false to false

    @Test
    fun `a starved session lengthens the streak`() {
        assertEquals(1, streakAfter(starved))
        assertEquals(3, streakAfter(starved, starved, starved))
    }

    @Test
    fun `one rendered frame clears it`() {
        assertEquals(0, streakAfter(starved, starved, healthy))
    }

    @Test
    fun `a session that never handshook neither counts nor clears`() {
        assertEquals(2, streakAfter(starved, starved, neverConnected))
        assertEquals(0, streakAfter(neverConnected, neverConnected))
    }

    @Test
    fun `advises only once the run is long enough to mean something`() {
        assertFalse(VideoStarvationPolicy.shouldAdvise(0))
        assertFalse(VideoStarvationPolicy.shouldAdvise(1))
        assertFalse(VideoStarvationPolicy.shouldAdvise(2))
        assertTrue(VideoStarvationPolicy.shouldAdvise(3))
    }

    @Test
    fun `says it once, not on every reconnect after`() {
        // The 2.4GHz measurement produced 32 of these in under five minutes.
        val advised = (1..32).count { VideoStarvationPolicy.shouldAdvise(it) }

        assertEquals(1, advised)
    }

    // --- acting on the streak, not only reporting it ---------------------------------------------

    @Test
    fun `the cap holds for as long as the streak does, unlike the advice`() {
        assertFalse(VideoStarvationPolicy.shouldCap(2))
        assertTrue(VideoStarvationPolicy.shouldCap(3))
        // The advice is an edge and says itself once; the cap is a standing condition.
        for (streak in 4..32) {
            assertFalse("advice at $streak", VideoStarvationPolicy.shouldAdvise(streak))
            assertTrue("cap at $streak", VideoStarvationPolicy.shouldCap(streak))
        }
    }

    @Test
    fun `the cap and the advice agree on when the run is long enough`() {
        assertEquals(
            VideoStarvationPolicy.shouldAdvise(VideoStarvationPolicy.ADVISE_AFTER_STARVED_SESSIONS),
            VideoStarvationPolicy.shouldCap(VideoStarvationPolicy.ADVISE_AFTER_STARVED_SESSIONS)
        )
    }

    @Test
    fun `a capped session that renders clears the streak, which is why the cap is kept elsewhere`() {
        // The oscillation this guards: capping is what makes the session render, so a cap released
        // on a cleared streak would be earned again three sessions later, forever. shouldCap goes
        // false here on purpose and Settings.videoProfileStarvationCap is what does not.
        var streak = streakAfter(starved, starved, starved)
        assertTrue(VideoStarvationPolicy.shouldCap(streak))
        streak = VideoStarvationPolicy.nextStreak(streak, reachedHandshake = true, renderedAnyFrame = true)
        assertEquals(0, streak)
        assertFalse(VideoStarvationPolicy.shouldCap(streak))
    }

    @Test
    fun `a cleared streak can advise again on the next run`() {
        var streak = streakAfter(starved, starved, starved)
        assertTrue(VideoStarvationPolicy.shouldAdvise(streak))

        streak = VideoStarvationPolicy.nextStreak(streak, reachedHandshake = true, renderedAnyFrame = true)
        assertEquals(0, streak)

        repeat(VideoStarvationPolicy.ADVISE_AFTER_STARVED_SESSIONS) {
            streak = VideoStarvationPolicy.nextStreak(streak, reachedHandshake = true, renderedAnyFrame = false)
        }
        assertTrue(VideoStarvationPolicy.shouldAdvise(streak))
    }
}
