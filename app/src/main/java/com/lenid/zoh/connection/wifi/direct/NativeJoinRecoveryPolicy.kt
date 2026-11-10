package com.lenid.zoh.connection.wifi.direct

/**
 * What the Native AA join watchdog should do when no phone has joined the group.
 *
 * Recreating repairs a phone that took credentials and could not join. A phone that has not opened
 * the Android Auto Bluetooth channel since this arming was never handed any, so the group is not
 * what is wrong and the interface cycle is spent for nothing. Scoped to the arming rather than the
 * process, or a reconnect attempt inherits the last session's dial and recreates for a phone that
 * has asked for nothing.
 */
object NativeJoinRecoveryPolicy {

    enum class Step {
        /** Tear the group down and build a fresh one. */
        RECREATE,

        /** Leave the group up: nothing has asked to join it yet. */
        HOLD_PHONE_NEVER_DIALLED,

        /** The recreate budget is spent. */
        GIVE_UP,
    }

    fun step(
        phoneDialledThisArming: Boolean,
        recreateCount: Int,
        maxRecreates: Int,
    ): Step = when {
        !phoneDialledThisArming -> Step.HOLD_PHONE_NEVER_DIALLED
        recreateCount >= maxRecreates -> Step.GIVE_UP
        else -> Step.RECREATE
    }
}
