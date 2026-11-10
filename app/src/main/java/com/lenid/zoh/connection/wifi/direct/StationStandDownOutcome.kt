package com.lenid.zoh.connection.wifi.direct

/**
 * What the last stand-down decision actually did to this unit's own WiFi association.
 *
 * The deciding lines are INFO and rotate out of a head unit's buffer within a minute, so a reporter
 * capture of a stuttering session routinely cannot say which arm ran. This is the record the
 * session banner and the scan summary carry instead, because those two survive a rotated export.
 */
enum class StationStandDownOutcome(
    /** The token the session banner carries. */
    val token: String,
    /** How a scan summary names the station these scans were counted on, or null to say nothing. */
    val scanClause: String?
) {
    /** No bring-up has decided yet in this process. */
    UNKNOWN("unknown", null),

    STOOD_DOWN("stood-down", "The station was stood down for this bring-up."),

    /** Asked to leave and still there when the supplicant was read back. */
    STILL_JOINED("still-joined", "The station was asked to stand down and was still joined afterwards."),

    /** Left joined on purpose: the mode said so, or the network could not be named. */
    JOINED("joined", "The station stayed joined to its own network."),

    NOT_JOINED("not-joined", "The station was not joined to a network."),

    UNAVAILABLE("unavailable", "The platform would not let the station be stood down."),

    FAILED("failed", "The stand-down failed.");
}
