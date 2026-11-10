package com.lenid.zoh.connection.wifi.modes.nativeaa

import com.lenid.zoh.connection.wifi.modes.nativeaa.PokeReadinessPolicy.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class PokeReadinessPolicyTest {

    @Test
    fun `an open listener is poked`() {
        assertEquals(
            Step.POKE,
            PokeReadinessPolicy.step(isRunning = true, listenersClosed = false, sessionUp = false)
        )
    }

    @Test
    fun `a manager that was never started never pokes`() {
        listOf(false, true).forEach { closed ->
            listOf(false, true).forEach { up ->
                assertEquals(
                    Step.STOP,
                    PokeReadinessPolicy.step(isRunning = false, listenersClosed = closed, sessionUp = up)
                )
            }
        }
    }

    @Test
    fun `a listener closed by a session that has ended is reopened, not waited on`() {
        assertEquals(
            Step.REARM_FIRST,
            PokeReadinessPolicy.step(isRunning = true, listenersClosed = true, sessionUp = false)
        )
    }

    @Test
    fun `a live session keeps the listener it closed`() {
        assertEquals(
            Step.STOP,
            PokeReadinessPolicy.step(isRunning = true, listenersClosed = true, sessionUp = true)
        )
    }

    @Test
    fun `a live session does not stop a poke whose listener is open`() {
        // The live-session veto belongs to NativeHandoffPolicy.loopStep; this policy only answers
        // for the listener, so an open one is still POKE.
        assertEquals(
            Step.POKE,
            PokeReadinessPolicy.step(isRunning = true, listenersClosed = false, sessionUp = true)
        )
    }
}
