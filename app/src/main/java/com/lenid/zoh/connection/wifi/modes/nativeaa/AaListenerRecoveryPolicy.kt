package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * What to do when the Android Auto RFCOMM accept loop stops serving.
 *
 * The loop used to end on a socket error having recorded nothing, so `start()` early-returned on a
 * manager that was still "running", the rearm reported the listeners open, and the wake poke
 * believed it could help. Measured on the rig: the head unit's own radio bouncing killed the loop
 * and nothing reopened it for the life of the process.
 */
object AaListenerRecoveryPolicy {

    /** How many times a lost listener is reopened before waiting for the radio to say it is back. */
    const val MAX_REOPEN_ATTEMPTS = 4

    /** How long to wait before each attempt; the radio takes a moment to come back after a bounce. */
    const val REOPEN_DELAY_MS = 2_000L

    /** Why the accept loop stopped serving. */
    enum class Exit {
        /** The handoff closed it on purpose; the session owns what happens next. */
        HANDOFF,

        /** The manager was stopped; nothing is meant to be listening. */
        STOPPED,

        /** It died under us, and only this leaves the unit unable to answer the phone. */
        FAILED,
    }

    fun exitKind(closedForSession: Boolean, running: Boolean): Exit = when {
        closedForSession -> Exit.HANDOFF
        !running -> Exit.STOPPED
        else -> Exit.FAILED
    }

    /**
     * Whether a lost listener may be reopened now.
     *
     * A radio that is off cannot host one, and retrying into it only spends the budget that a real
     * transient needs; the radio coming back is a separate event and resets the count.
     */
    fun mayReopen(adapterEnabled: Boolean, attemptsSoFar: Int): Boolean =
        adapterEnabled && attemptsSoFar < MAX_REOPEN_ATTEMPTS
}
