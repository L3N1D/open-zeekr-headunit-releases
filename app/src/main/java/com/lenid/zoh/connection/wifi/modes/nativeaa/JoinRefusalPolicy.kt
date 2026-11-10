package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * How long to wait before offering the credentials again to a phone that keeps refusing them.
 *
 * A phone that answers every message and then reports it could not join is invisible to the
 * handshake backoff, which only counts handshakes the phone was silent through. Measured on a
 * K706 with the phone's own hotspot up: ten full credential handoffs in 61.5 s, 6.83 s apart,
 * each one a "Connecting to Android Auto" flicker on the phone.
 *
 * Widening rather than stopping, because the cause is usually outside the app: the user turning
 * their hotspot off has to be enough to recover, with nothing else to press.
 */
object JoinRefusalPolicy {

    /** Refusals tolerated at the ordinary cadence before the wait widens at all. */
    const val REFUSALS_AT_NORMAL_CADENCE = 1

    /** Refusals before the wait opens out to its ceiling. */
    const val REFUSALS_BEFORE_CEILING = 5

    const val WIDENED_DELAY_MS = 30_000L
    const val CEILING_DELAY_MS = 120_000L

    /**
     * The gap to leave after [consecutiveRefusals] in a row, given [normalDelayMs] as the cadence
     * when nothing is wrong.
     *
     * The first refusal keeps that cadence: a phone can lose one join to a slow DHCP lease and
     * take the next one.
     */
    fun retryDelayMs(consecutiveRefusals: Int, normalDelayMs: Long): Long = when {
        consecutiveRefusals <= REFUSALS_AT_NORMAL_CADENCE -> normalDelayMs
        consecutiveRefusals < REFUSALS_BEFORE_CEILING -> maxOf(normalDelayMs, WIDENED_DELAY_MS)
        else -> maxOf(normalDelayMs, CEILING_DELAY_MS)
    }

    /**
     * Whether this refusal is the one that widens the gap, so the reason is logged once rather
     * than on every attempt.
     */
    fun isFirstWidening(consecutiveRefusals: Int): Boolean =
        consecutiveRefusals == REFUSALS_AT_NORMAL_CADENCE + 1

    /**
     * How much of the gap after a refusal at [lastRefusalAtMs] is still to run at [now].
     *
     * Read wherever a wake round starts as well as where one ends: the phone that refuses takes
     * the credentials over Bluetooth first, and that handshake cancels the retry loop, so a gap
     * waited only at the loop's foot never runs at all.
     */
    fun remainingDelayMs(
        consecutiveRefusals: Int,
        normalDelayMs: Long,
        lastRefusalAtMs: Long,
        now: Long,
    ): Long {
        if (lastRefusalAtMs <= 0L) return 0L
        val gap = retryDelayMs(consecutiveRefusals, normalDelayMs)
        return (lastRefusalAtMs + gap - now).coerceIn(0L, gap)
    }
}
