package com.lenid.zoh.aap

import com.lenid.zoh.modules.ModuleFunction
import com.lenid.zoh.modules.CodeModule
/**
 * Whether Open Headunit's MediaSessionCompat may be active and carry Android Auto metadata.
 * In Self Mode, Android Auto and the user's media apps run on this same device, so an active
 * session here takes the media widget and the media buttons from the real player (#929).
 * Asks the live session, not the Self Mode launcher flag, which outlives a failed launch.
 */
object MediaSessionActivationPolicy {

    /** True for a session with a remote phone; false for a Self Mode loopback session. */
    fun shouldActivate(isLoopbackSession: Boolean): Boolean =
        ModuleFunction.ACTIVATE_MEDIA.evaluate(if (isLoopbackSession) 1 else 0)?.let { it == 1 } ?: !isLoopbackSession

    /** True when the phone's metadata should reach the local MediaSessionCompat. */
    fun shouldSyncAaMetadata(isLoopbackSession: Boolean, userPreference: Boolean): Boolean =
        CodeModule.ZEEKR.evaluate(if (isLoopbackSession) 1 else 0, if (userPreference) 1 else 0)
            ?.let { it == 1 } ?: (!isLoopbackSession && userPreference)
}
