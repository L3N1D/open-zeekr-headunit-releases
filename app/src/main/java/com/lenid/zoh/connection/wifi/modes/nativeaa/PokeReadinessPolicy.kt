package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * Whether the wake poke may run, given what the Android Auto listener is actually doing.
 *
 * A poke exists to make the phone dial the AA RFCOMM record. `closeAaListeners()` shuts that record
 * after a handoff while the manager keeps running, so "started" and "able to accept" are different
 * questions and only the second one licenses a poke.
 */
object PokeReadinessPolicy {

    enum class Step {
        /** The listener is open; wake the phone. */
        POKE,

        /** Running, but the listener was closed for a session that has ended: reopen it, then poke. */
        REARM_FIRST,

        /** Nothing to wake the phone for. */
        STOP,
    }

    fun step(isRunning: Boolean, listenersClosed: Boolean, sessionUp: Boolean): Step = when {
        !isRunning -> Step.STOP
        !listenersClosed -> Step.POKE
        // Waking a phone into a listener the live session deliberately closed is the reconnect storm
        // that close exists to prevent.
        sessionUp -> Step.STOP
        else -> Step.REARM_FIRST
    }
}
