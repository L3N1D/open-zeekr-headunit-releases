package com.lenid.zoh.connection.wifi.direct

/**
 * Whether a second Native AA bring-up landing on the heels of one already started is a duplicate.
 *
 * Two bring-ups run the whole remove-and-recreate each, one of them gets BUSY, and the BUSY handler
 * removes the group the other just made - under a phone that has already joined it, if one had.
 * Measured at 12ms apart on a GT7H-CAR: the launcher deferred behind its hotspot teardown while the
 * P2P interface that teardown cycled replayed ENABLED to the receiver.
 */
object NativeBringUpReentryPolicy {

    /**
     * Wide enough for the interface cycle a bring-up provokes, and below the 2s the pre-API-29
     * WiFi-enable retry waits before re-entering on purpose.
     */
    const val DUPLICATE_WINDOW_MS = 1_500L

    /** Whether a bring-up asked for now duplicates one started at [lastStartedAtMs]. */
    fun isDuplicate(nowMs: Long, lastStartedAtMs: Long): Boolean =
        lastStartedAtMs != 0L && nowMs - lastStartedAtMs in 0 until DUPLICATE_WINDOW_MS
}
