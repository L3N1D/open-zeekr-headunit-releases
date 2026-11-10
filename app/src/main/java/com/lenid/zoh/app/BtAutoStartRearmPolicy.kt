package com.lenid.zoh.app

import com.lenid.zoh.connection.wifi.WifiLauncherMode

/**
 * What a Bluetooth auto-start does to the connection stack. Native AA is the only mode that has to
 * be rebuilt, because a completed handoff closes its listeners for good; the others keep listening
 * while armed, so there the most to do is arm a launcher that nothing has armed.
 */
data class BtAutoStartActions(
    val clearUserExit: Boolean,
    val forceRearmWireless: Boolean,
    val armWirelessIfIdle: Boolean
) {
    val doesNothing: Boolean
        get() = !clearUserExit && !forceRearmWireless && !armWirelessIfIdle

    companion object {
        val NONE = BtAutoStartActions(clearUserExit = false, forceRearmWireless = false, armWirelessIfIdle = false)
    }
}

/**
 * Decides what a Bluetooth auto-start does, per wireless mode and transport. The mode comes from the
 * stored setting, never the launcher, which a Native user exit nulls; everything else is asked of the
 * launcher and is nullable. Native is forced only when it cannot accept at all, so [groupUp] and
 * [networkComingUp] both veto. USB is excluded: it has its own attach and detach triggers.
 */
object BtAutoStartRearmPolicy {

    fun actionsFor(
        mode: WifiLauncherMode,
        wirelessSelected: Boolean,
        sessionUp: Boolean,
        wirelessArmed: Boolean,
        handshakeActive: Boolean?,
        attemptInFlight: Boolean?,
        groupUp: Boolean?,
        networkComingUp: Boolean?
    ): BtAutoStartActions {
        // A network that has been asked for and has not answered is work in progress, exactly like
        // an attempt in flight: everything below would read it as "cannot accept" and rebuild.
        // An active handshake suppresses everything only while its group is still up; a handshake
        // stranded with no network is a state to rebuild out of, not one to protect.
        if (vetoReason(sessionUp, handshakeActive, attemptInFlight, groupUp, networkComingUp) != null) {
            return BtAutoStartActions.NONE
        }

        // Native is forced, but not onto a unit whose user connects by cable: a rearm there would
        // create the network and poke the phone that just walked past.
        val forceRearm = mode == WifiLauncherMode.NATIVE && wirelessSelected
        return BtAutoStartActions(
            clearUserExit = true,
            forceRearmWireless = forceRearm,
            armWirelessIfIdle = !forceRearm && wirelessSelected && !wirelessArmed
        )
    }

    /**
     * Why this arrival is left alone, or null when nothing vetoes it.
     *
     * A veto used to leave no line at all, so a round that measured one had to infer which of the
     * four fired from the absence of every branch below it. The order is [actionsFor]'s.
     */
    fun vetoReason(
        sessionUp: Boolean,
        handshakeActive: Boolean?,
        attemptInFlight: Boolean?,
        groupUp: Boolean?,
        networkComingUp: Boolean?
    ): String? = when {
        sessionUp -> "a session is already up"
        attemptInFlight == true -> "a handshake attempt is already in flight"
        networkComingUp == true -> "the network has been asked for and has not answered yet"
        handshakeActive == true && groupUp != false -> "a handshake is running on a group that is still up"
        else -> null
    }
}
