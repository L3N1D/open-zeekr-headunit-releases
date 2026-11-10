package com.lenid.zoh.connection.wifi

/**
 * Whether a request to arm the wireless stack should be refused as already answered.
 *
 * The manager keeps the launcher it last used after stopping it, so the "same start-configuration"
 * refusal was being given on behalf of one that had been torn down. Pure, so both halves are tests.
 */
object WifiLauncherRestartPolicy {

    /**
     * @param activeIsStarted whether the launcher the manager holds is still running. A user exit
     *   stops it without clearing it, and a stopped launcher has nothing to refuse on behalf of.
     * @param sameConfiguration what the held launcher answers about the requested one.
     * @param force an explicit request to rebuild whatever is there.
     */
    fun refusesRestart(
        activeIsStarted: Boolean,
        sameConfiguration: Boolean,
        force: Boolean,
    ): Boolean = !force && activeIsStarted && sameConfiguration
}
