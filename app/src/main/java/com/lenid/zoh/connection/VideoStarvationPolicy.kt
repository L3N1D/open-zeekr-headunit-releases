package com.lenid.zoh.connection

/**
 * When to tell the user that the link cannot carry the video stream they have asked for.
 *
 * The failure this exists to name, measured 2026-08-05 on a head unit hotspot: over a 2.4 GHz
 * access point at 1080p/60 the phone completed the Bluetooth handshake, joined the network, opened
 * the AAP session and the video channel — and then closed the socket 0.04–4.2 s later, having sent
 * **not one video frame**. Thirty-two times in under five minutes, each cycle looking like a fresh,
 * healthy connection right up to the moment it ended. On the same access point, same band, same
 * everything, an 800x480/30 stream held for as long as it was watched; and at 1080p/60 on 5 GHz it
 * held too. So this is a throughput ceiling, not a broken route — and the phone's own reason
 * (`GAL_SOCKET_CONNECTION_FAILED`) never reaches our log.
 *
 * We cannot read the band an access point is running on: `SoftApInfo`'s frequency needs
 * NETWORK_SETTINGS, which no ordinary app holds. What we can see is the shape — a session that
 * reached SSL and then died having rendered nothing, over and over. That is specific enough to act
 * on, and nothing else produces it: a phone that never joins never gets this far, and a decoder
 * that cannot handle the codec renders nothing but does not take the socket down with it.
 *
 * Deliberately a streak rather than a single occurrence. One starved session is ordinary — a phone
 * being unplugged mid-bring-up looks exactly like this once — and advice given on the strength of
 * one is advice that will sometimes be wrong.
 *
 * **This used to only advise**, and telling the user to lower the resolution by hand is what an
 * Android 4.4 tablet needed three failed bring-ups to be told. [shouldCap] is the same streak
 * acting on itself: the profile comes down and the picture arrives.
 */
object VideoStarvationPolicy {

    /** How many sessions in a row must end without a frame before the advice is worth giving. */
    const val ADVISE_AFTER_STARVED_SESSIONS = 3

    /**
     * Whether the session that just ended should produce the advice, given how many have now ended
     * in a row without rendering a frame.
     *
     * True on exactly the [ADVISE_AFTER_STARVED_SESSIONS]th, never after: the reconnect loop this
     * fires inside runs every few seconds, and a line repeated thirty times is one a reader learns
     * to scroll past. Say it once, and let the streak keep counting for anyone reading the numbers.
     */
    fun shouldAdvise(consecutiveStarvedSessions: Int): Boolean =
        consecutiveStarvedSessions == ADVISE_AFTER_STARVED_SESSIONS

    /**
     * Whether the next session should be offered a lower profile than the user asked for.
     *
     * The complement of [shouldAdvise]'s "say it once": this is a standing condition rather than an
     * edge, so it holds for as long as the streak does, and the same streak that produced the
     * advice produces the cap.
     *
     * **What retires it is not this function.** The cap is what makes the next session render, and
     * a rendering session is what clears the streak, so a cap released on a cleared streak would
     * uncap, starve three more times and be earned again forever. The conclusion is kept instead
     * (`Settings.videoProfileStarvationCap`) and only the user changing the resolution or the frame
     * rate takes it back off, which is the same shape as the playback-focus latch.
     */
    fun shouldCap(consecutiveStarvedSessions: Int): Boolean =
        consecutiveStarvedSessions >= ADVISE_AFTER_STARVED_SESSIONS

    /**
     * The streak after a session that [reachedHandshake] and rendered frames or not.
     *
     * A session that never reached the handshake is not counted either way — it never had a chance
     * to carry video, so it neither proves starvation nor clears a run of it.
     */
    fun nextStreak(current: Int, reachedHandshake: Boolean, renderedAnyFrame: Boolean): Int = when {
        !reachedHandshake -> current
        renderedAnyFrame -> 0
        else -> current + 1
    }
}
