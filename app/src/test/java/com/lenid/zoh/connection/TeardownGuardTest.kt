package com.lenid.zoh.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeardownGuardTest {

    @Test
    fun `the close runs when the teardown succeeds`() {
        val calls = mutableListOf<String>()
        val errors = mutableListOf<String>()

        TeardownGuard.runThenClose(
            teardown = { calls.add("teardown") },
            close = { calls.add("close") },
            onError = { phase, _ -> errors.add(phase) }
        )

        assertEquals(listOf("teardown", "close"), calls)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `the close still runs when the teardown throws`() {
        // The regression this exists for: a socket left open wedges the phone's head unit server.
        val calls = mutableListOf<String>()
        val errors = mutableListOf<String>()

        TeardownGuard.runThenClose(
            teardown = { calls.add("teardown"); throw IllegalStateException("boom") },
            close = { calls.add("close") },
            onError = { phase, _ -> errors.add(phase) }
        )

        assertEquals(listOf("teardown", "close"), calls)
        assertEquals(listOf("teardown"), errors)
    }

    @Test
    fun `a throwing close is reported and does not propagate`() {
        val errors = mutableListOf<String>()

        TeardownGuard.runThenClose(
            teardown = {},
            close = { throw IllegalStateException("boom") },
            onError = { phase, _ -> errors.add(phase) }
        )

        assertEquals(listOf("close"), errors)
    }

    @Test
    fun `both phases throwing are each reported once, with their own exception`() {
        val reported = mutableListOf<Pair<String, String>>()

        TeardownGuard.runThenClose(
            teardown = { throw IllegalStateException("teardown failed") },
            close = { throw IllegalStateException("close failed") },
            onError = { phase, e -> reported.add(phase to (e.message ?: "")) }
        )

        assertEquals(
            listOf("teardown" to "teardown failed", "close" to "close failed"),
            reported
        )
    }

    @Test
    fun `the close never runs before the teardown`() {
        val calls = mutableListOf<String>()

        TeardownGuard.runThenClose(
            teardown = { calls.add("teardown") },
            close = { calls.add("close") },
            onError = { _, _ -> }
        )

        assertEquals("teardown", calls.first())
    }
}
