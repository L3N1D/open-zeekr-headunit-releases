package com.lenid.zoh.connection.wifi

import com.lenid.zoh.app.BtAutoStartRearmPolicy

/**
 * A relaunch while the unit sleeps armed the radios and the wake poke within seconds.
 * Automatic bring-ups wait until the screen comes on; only the user's own request passes.
 */
object WirelessSleepHold {

    /** null means no hold; otherwise the OR of every held bring-up's force flag. */
    @Volatile
    private var heldForce: Boolean? = null

    /**
     * The screen alone decides: an ACC-off with no screen cycle after it would hold a lit unit with
     * nothing to replay it. A screen that cannot be read counts as awake.
     */
    fun isAsleep(screenInteractive: Boolean?): Boolean = screenInteractive == false

    /**
     * A formed session drops the hold, since its own end re-arms wireless. An attempt in flight
     * replays unforced, so the arbiter owes the bring-up back if that attempt fails.
     */
    fun replayForce(held: Boolean?, connected: Boolean, connecting: Boolean): Boolean? = when {
        held == null || connected -> null
        connecting -> false
        else -> held
    }

    /**
     * A forced hold waits out an attempt in flight instead: unforced, it meets the restart
     * refusal against a stack that is still started, and the arbiter never learns it is owed.
     */
    fun waitsForAttempt(held: Boolean, connected: Boolean, connecting: Boolean): Boolean =
        held && !connected && connecting

    /**
     * The replay asks what the Bluetooth auto-start asks, so a phone that arrives with the screen
     * does not get a second rebuild under its join. Null means the replay goes ahead.
     */
    fun replayVeto(force: Boolean, networkComingUp: Boolean?, attemptInFlight: Boolean?): String? =
        if (!force) null
        else BtAutoStartRearmPolicy.vetoReason(
            sessionUp = false,
            handshakeActive = null,
            attemptInFlight = attemptInFlight,
            groupUp = null,
            networkComingUp = networkComingUp
        )

    /**
     * A bring-up that started after the take already rebuilt the stack, so the replay does not
     * force a second rebuild under the phone's join.
     */
    fun replayKeepsForce(force: Boolean, startsAtTake: Int, startsNow: Int): Boolean =
        force && startsNow == startsAtTake

    fun refusesBringUp(asleep: Boolean, userRequested: Boolean): Boolean =
        asleep && !userRequested

    /** True while the stack runs on a bring-up the user asked for, which the radio gate obeys too. */
    @Volatile
    var startedByUser: Boolean = false
        private set

    /** Bring-ups that actually started; the replay compares it with [startsAtTake]. */
    @Volatile
    var starts: Int = 0
        private set

    @Volatile
    var startsAtTake: Int = 0
        private set

    fun refusesRadioEnable(asleep: Boolean, startedByUser: Boolean): Boolean =
        asleep && !startedByUser

    /** A join recovery in the dark only moves the group's address; the wake rebuilds it instead. */
    fun refusesGroupRecreate(asleep: Boolean, startedByUser: Boolean): Boolean =
        asleep && !startedByUser

    /** The stack is starting, so a held bring-up has nothing left to replay. */
    @Synchronized
    fun started(userRequested: Boolean) {
        heldForce = null
        startedByUser = userRequested
        starts++
    }

    /** A stop ends the arming the user asked for, but not a bring-up held for the wake. */
    @Synchronized
    fun stopped() {
        startedByUser = false
    }

    /** Returns true only for the first hold since the last [take], so callers log once. */
    @Synchronized
    fun hold(force: Boolean): Boolean {
        val first = heldForce == null
        heldForce = (heldForce ?: false) || force
        return first
    }

    /** The force flag of the held bring-up, or null when nothing is held; an empty take keeps the baseline. */
    @Synchronized
    fun take(): Boolean? {
        val v = heldForce ?: return null
        heldForce = null
        startsAtTake = starts
        return v
    }

    @Synchronized
    fun clear() {
        heldForce = null
        startedByUser = false
    }
}
