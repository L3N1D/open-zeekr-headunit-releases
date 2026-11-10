package com.lenid.zoh.main

/**
 * Whether to offer Bluetooth auto-start for the phone that has connected to this unit.
 *
 * Auto-start used to turn itself on: a completed handshake wrote the peer into the trigger list, so
 * a user who never asked found the app launching itself. Asking once, about the one phone that can
 * be meant, is what replaces that.
 *
 * Pure, so every combination is a unit test rather than a device.
 */
object AutoStartOfferPolicy {

    /** The offer answers itself after this, so it never holds a driver's screen for longer. */
    const val OFFER_TIMEOUT_MS = 20_000L

    /**
     * @param phonesPaired bonded devices classified as phones, never the raw bond count: watches,
     *   dongles and car radios all advertise the record the poke dials.
     * @param connectedMac the phone this session was with, empty when it cannot be named.
     * @param answeredMacs phones already asked about, so a "no" is not asked again.
     * @param autoStartConfigured whether a trigger device is already stored.
     */
    fun offers(
        phonesPaired: Int,
        connectedMac: String,
        answeredMacs: Set<String>,
        autoStartConfigured: Boolean,
    ): Boolean = when {
        // A stored trigger is the user's own choice whatever else is paired, so it is never
        // cleared here. The handshake stopped writing this list in favour of the wake list.
        phonesPaired != 1 || connectedMac.isEmpty() -> false
        // Already set up, by this offer or by hand: there is nothing left to offer.
        autoStartConfigured -> false
        answeredMacs.any { it.equals(connectedMac, ignoreCase = true) } -> false
        else -> true
    }
}
