package com.lenid.zoh.aap

/**
 * Whether a return to the screen must re-arm keyframe recovery itself: a kept surface (TEXTURE)
 * fires no surface callback, so nothing else would arm it after the decoder stopped on onStop.
 */
object ReturnRearmPolicy {
    fun shouldRearm(stoppedOnStop: Boolean, surfaceSet: Boolean, lastSurfaceSetMs: Long, resumedAtMs: Long): Boolean =
        stoppedOnStop && surfaceSet && lastSurfaceSetMs < resumedAtMs
}
