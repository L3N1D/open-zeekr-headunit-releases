package com.lenid.zoh.aap

/**
 * How long the projection activity holds its reconnecting overlay before giving up and finishing.
 *
 * A wired reconnect used to get a flat 8 s, which is shorter than the app's own worst case for the
 * recovery it has to cover: 3 s before the service even rescans, then an AOA switch
 * `UsbAccessoryHandoffPolicy` sizes at 10 s. So a phone that re-enumerated in normal mode was given
 * up on while its switch was still running, and the session came back to a home screen.
 *
 * The answer is evidence rather than a bigger number. A bus that stays empty still ends at the base,
 * because nothing is coming back; a reconnect that is visibly in flight buys [PROGRESS_GRACE_MS]
 * from each sign of life, bounded by [CEILING_MS] so it cannot hold the overlay forever.
 */
object ReconnectGracePolicy {

    /** A wired link with nothing coming back. Unchanged from what shipped. */
    const val BASE_USB_MS = 8_000L

    /** A wireless link with nothing coming back. Unchanged from what shipped. */
    const val BASE_WIRELESS_MS = 20_000L

    /**
     * What one sign of life is worth, from the moment it arrives.
     *
     * `UsbAccessoryHandoffPolicy.SWITCH_CLAIM_TTL_MS` (10 s) plus the connect retries under it, so
     * a switch that has only just started can still finish.
     */
    const val PROGRESS_GRACE_MS = 14_000L

    /** Nothing holds the overlay past this, however many signals arrive. */
    const val CEILING_MS = 45_000L

    /** Why the wait ended, for the line the give-up logs. */
    enum class Ended { BASE_EXPIRED, PROGRESS_WENT_STALE, CEILING_REACHED }

    fun baseFor(isUsb: Boolean): Long = if (isUsb) BASE_USB_MS else BASE_WIRELESS_MS

    /**
     * @param sinceProgressMs how long ago the reconnect last showed a sign of life, or null when it
     *   has shown none since the disconnect.
     */
    fun keepWaiting(isUsb: Boolean, sinceDisconnectMs: Long, sinceProgressMs: Long?): Boolean =
        endedBecause(isUsb, sinceDisconnectMs, sinceProgressMs) == null

    /** Null while the wait should continue, otherwise which bound ended it. */
    fun endedBecause(isUsb: Boolean, sinceDisconnectMs: Long, sinceProgressMs: Long?): Ended? = when {
        sinceDisconnectMs >= CEILING_MS -> Ended.CEILING_REACHED
        sinceProgressMs == null ->
            if (sinceDisconnectMs >= baseFor(isUsb)) Ended.BASE_EXPIRED else null
        sinceProgressMs >= PROGRESS_GRACE_MS && sinceDisconnectMs >= baseFor(isUsb) ->
            Ended.PROGRESS_WENT_STALE
        else -> null
    }
}
