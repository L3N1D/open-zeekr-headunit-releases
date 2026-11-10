package com.lenid.zoh.connection

/**
 * How long the status pill stays down after the phone ended the session itself.
 *
 * A bye-bye is a deliberate disconnection, not a wireless failure, and the listeners reopening
 * behind it put "Creating network" back on screen 119 ms later. The stack is deliberately still
 * running - it just has nothing to announce yet.
 */
object PhoneExitQuietPolicy {

    /** The phone that is coming straight back took 501 ms to do it, measured twice. */
    const val QUIET_MS = 4_000L

    /** [phoneLeftAtMs] is 0 when no clean phone-side exit has happened this process. */
    fun suppressesPill(phoneLeftAtMs: Long, nowMs: Long): Boolean =
        phoneLeftAtMs != 0L && nowMs - phoneLeftAtMs in 0 until QUIET_MS

    /** What is left of the window, for scheduling the re-render that ends it. */
    fun remainingMs(phoneLeftAtMs: Long, nowMs: Long): Long =
        (phoneLeftAtMs + QUIET_MS - nowMs).coerceAtLeast(0L)
}
