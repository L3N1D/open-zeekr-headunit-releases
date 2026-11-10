package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * Whether the wake poke may start before the WiFi credentials exist.
 *
 * The wake used to be triggered only by a credential delivery, so the phone was not woken until the
 * group was up, its info had come back and its IP had resolved. The handshake does not need that
 * ordering: it sends the version exchange first and waits for credentials afterwards, so the
 * phone's own wake latency can run alongside the group forming instead of after it.
 */
object EarlyWakePolicy {

    /**
     * [listenersOpen] is the manager's "started, and the listener not closed for this session"
     * read. A phone woken to a head unit with no listener finds nothing to dial and the log still
     * says the poke worked.
     */
    fun mayWakeBeforeCredentials(
        listenersOpen: Boolean,
        credentialsPresent: Boolean,
        userExited: Boolean,
        sessionUp: Boolean,
    ): Boolean = listenersOpen && !credentialsPresent && !userExited && !sessionUp

    /**
     * Whether a `triggerPoke()` with a new credential key should replace the running loop.
     *
     * False when the only change is credentials arriving under a loop that started without them:
     * the loop re-reads them every pass, so restarting it throws away the head start an early wake
     * just bought and pays the entry wait again.
     */
    fun shouldRestartLoop(
        loopActive: Boolean,
        previousKey: Triple<String, String, String>?,
        newKey: Triple<String, String, String>,
    ): Boolean = when {
        !loopActive -> true
        previousKey == newKey -> false
        previousKey != null && isEmptyKey(previousKey) && !isEmptyKey(newKey) -> false
        else -> true
    }

    /**
     * Whether a wake loop may start with no credentials. Once one has brought the phone back to a
     * head unit with no network, the next wake waits for the credentials to exist.
     */
    fun mayStartWithoutCredentials(keyIsEmpty: Boolean, earlyWakeSpent: Boolean): Boolean =
        !keyIsEmpty || !earlyWakeSpent

    /**
     * Whether a handshake that ended for lack of credentials should stop the running wake loop.
     * Only a loop that started empty: one started by a credential delivery is the ordinary wake,
     * and a group that went away under it is re-delivered by the recreate.
     */
    fun stopAfterCredentialsFailure(loopStartedEmpty: Boolean, credentialsPresent: Boolean): Boolean =
        loopStartedEmpty && !credentialsPresent

    fun isEmptyKey(key: Triple<String, String, String>): Boolean =
        key.first.isEmpty() && key.second.isEmpty() && key.third.isEmpty()
}
