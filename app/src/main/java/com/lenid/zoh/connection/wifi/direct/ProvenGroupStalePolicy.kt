package com.lenid.zoh.connection.wifi.direct

/**
 * When a P2P group that has carried a session should be recreated anyway.
 *
 * [NativeHandoffPolicy.shouldRearmJoinWatchdogAfterClientLeft] keeps a proven group forever,
 * because a recreate moves its address out from under the profile the phone saved. That holds
 * right up until the phone stops coming back: measured on a 40 minute session the phone's own
 * stack left the group during a background scan, could no longer find it by scan, and the wake
 * poke loop ran for nine minutes without ever refreshing the WiFi side. The saved profile was
 * not working either, so keeping the group served nobody and only the WiFi button recovered it.
 */
object ProvenGroupStalePolicy {

    /**
     * Unanswered wake pokes before a proven group is treated as stale.
     *
     * About three minutes at the retry loop's 45 s cadence: long enough for a phone that is merely
     * slow to answer, short enough to beat the user reaching for the WiFi button themselves.
     */
    const val UNANSWERED_POKES_BEFORE_STALE = 4

    /**
     * Whether this group has stopped serving the phone it was proven with.
     *
     * A live session or an exchange in flight always wins: those are the two states a recreate
     * would break, and they are checked here rather than by the caller so the rule reads in one
     * place.
     */
    fun isStale(
        groupHasHostedSession: Boolean,
        sessionConnected: Boolean,
        handshakeInFlight: Boolean,
        unansweredPokes: Int,
    ): Boolean =
        groupHasHostedSession &&
            !sessionConnected &&
            !handshakeInFlight &&
            unansweredPokes >= UNANSWERED_POKES_BEFORE_STALE
}
