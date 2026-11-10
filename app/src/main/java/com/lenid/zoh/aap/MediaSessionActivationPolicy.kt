package com.lenid.zoh.aap

/**
 * Whether Open Headunit's MediaSessionCompat may be active and carry Android Auto metadata.
 * In Self Mode, Android Auto and the user's media apps run on this same device, so an active
 * session here takes the media widget and the media buttons from the real player (#929).
 * Asks the live session, not the Self Mode launcher flag, which outlives a failed launch.
 */
object MediaSessionActivationPolicy {

    /** True for a session with a remote phone; false for a Self Mode loopback session. */
    fun shouldActivate(isLoopbackSession: Boolean): Boolean = !isLoopbackSession

    /** True when the phone's metadata should reach the local MediaSessionCompat. */
    fun shouldSyncAaMetadata(isLoopbackSession: Boolean, userPreference: Boolean): Boolean =
        !isLoopbackSession && userPreference
}
