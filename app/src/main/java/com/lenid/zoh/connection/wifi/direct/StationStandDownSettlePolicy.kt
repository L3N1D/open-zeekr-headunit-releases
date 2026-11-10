package com.lenid.zoh.connection.wifi.direct

/**
 * When the group may be created after the station was asked to leave its own network.
 *
 * The wait used to be a flat post of [StationStandDown.VERIFY_DELAY_MS], paid in full even on units
 * that leave in a fraction of it. The same read that verified it afterwards answers the question
 * early, so the delay becomes a ceiling rather than a cost.
 */
object StationStandDownSettlePolicy {

    /** How often to ask whether the station has left. */
    const val POLL_MS = 100L

    /** Longest to wait; past this the group is created regardless, as it always was. */
    const val CEILING_MS = StationStandDown.VERIFY_DELAY_MS

    enum class Step {
        /** Still associated and there is time left. Ask again. */
        WAIT,

        /** It has left, or the ceiling is reached. Create the group. */
        CREATE
    }

    /**
     * [stillAssociated] is the supplicant read; null means it could not be read, which is not
     * evidence the station is still there and must not hold the group up.
     */
    fun step(stillAssociated: Boolean?, waitedMs: Long): Step =
        if (stillAssociated == true && waitedMs < CEILING_MS) Step.WAIT else Step.CREATE
}
