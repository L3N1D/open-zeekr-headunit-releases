package com.lenid.zoh.aap

/**
 * Whether stopping the service on a user exit has a wireless teardown owed to it first.
 *
 * The teardown runs on `AapService.serviceScope` and suspends on `awaitDisconnectComplete()` before
 * it reaches the launcher, so a `stopSelf()` that does not wait cancels that scope at the await and
 * leaves the P2P group up for the next bring-up to meet as a BUSY create.
 */
object ServiceStopWaitPolicy {

    /** Bound on the wait, so a teardown that never returns still stops the service. */
    const val TEARDOWN_TIMEOUT_MS = 5_000L

    /**
     * Asked before the disconnect, so the answer cannot depend on work the disconnect starts.
     *
     * @param sessionConnected whether there is a session to tear down at all.
     * @param wirelessLauncherActive whether a launcher holds a network. Both the P2P and the hotspot
     *   branches of the teardown are behind this, so neither needs naming here.
     */
    fun waitsForWirelessTeardown(sessionConnected: Boolean, wirelessLauncherActive: Boolean): Boolean =
        sessionConnected && wirelessLauncherActive
}
