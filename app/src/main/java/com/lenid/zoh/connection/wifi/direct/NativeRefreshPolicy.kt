package com.lenid.zoh.connection.wifi.direct

/**
 * What a credential refresh does to the Native AA group.
 *
 * The handshake asks for one every ten seconds while it waits for credentials, and before every
 * poke. It used to be a full teardown and recreate, which handed the phone a new network name in
 * the very window it was trying to join one. A group that is up and ours only needs its credentials
 * read again; one that was just asked for needs to be left to answer; only no group at all is
 * worth creating one.
 */
object NativeRefreshPolicy {

    /** How long a createGroup is given to answer before a refresh stops waiting on it. */
    const val CREATE_GRACE_MS = 15_000L

    /**
     * How long an accepted create is given to turn into a group: the twenty one-second group-info
     * reads, plus one. A refresh inside it used to remake the group underneath those reads.
     */
    const val GROUP_INFO_WINDOW_MS = 21_000L

    enum class Action { REDELIVER, WAIT, RECREATE }

    /** Whether a create asked for this long ago is still owed an answer. */
    fun withinCreateGrace(elapsedMs: Long?): Boolean =
        elapsedMs != null && elapsedMs in 0 until CREATE_GRACE_MS

    /** Whether a create accepted this long ago is still owed a group. */
    fun withinGroupInfoWindow(elapsedMs: Long?): Boolean =
        elapsedMs != null && elapsedMs in 0 until GROUP_INFO_WINDOW_MS

    /**
     * The same question read off the stamp, for callers that have to decide before the group can
     * answer. A create is several async hops from the call that asked for it, and anything that
     * tears the mode down in between starts a second one underneath the first.
     */
    fun createInFlight(requestedAtMs: Long, nowMs: Long): Boolean =
        requestedAtMs != 0L && withinCreateGrace(nowMs - requestedAtMs)

    fun decide(
        groupExists: Boolean,
        isGroupOwner: Boolean,
        createInFlightForMs: Long?,
        acceptedCreatePendingForMs: Long? = null,
    ): Action = when {
        groupExists && isGroupOwner -> Action.REDELIVER
        withinCreateGrace(createInFlightForMs) -> Action.WAIT
        withinGroupInfoWindow(acceptedCreatePendingForMs) -> Action.WAIT
        else -> Action.RECREATE
    }

    /** How long a WAIT asks again in: once the longer of the two windows is up, plus a little. */
    fun recheckDelayMs(createInFlightForMs: Long?, acceptedCreatePendingForMs: Long?): Long {
        val graceLeft = if (withinCreateGrace(createInFlightForMs)) CREATE_GRACE_MS - createInFlightForMs!! else 0L
        val windowLeft = if (withinGroupInfoWindow(acceptedCreatePendingForMs)) GROUP_INFO_WINDOW_MS - acceptedCreatePendingForMs!! else 0L
        return maxOf(graceLeft, windowLeft) + 500L
    }
}
