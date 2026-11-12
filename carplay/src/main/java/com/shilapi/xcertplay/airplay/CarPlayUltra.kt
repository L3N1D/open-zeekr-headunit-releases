package com.shilapi.xcertplay.airplay

/**
 * Experimental CarPlay Ultra declarations, off by default; see docs/CARPLAY_ULTRA.md.
 *
 * In SETUP the iPhone proposes session features that ordinary CarPlay leaves off. Apple's WWDC24
 * architecture session names a per-display "UI sync" channel and a vehicle-state interface for the
 * cluster; `uiSync` and `vehicleStateProtocol` are the matching proposals. With the experiment on,
 * Tarmac enables those the iPhone proposes, accepts the data streams that follow and logs what
 * arrives on them, so the protocol is learnt from the iPhone itself.
 */
object CarPlayUltra {
    /** Proposed by iOS in SETUP (seen with iOS 27) and enabled by ordinary CarPlay: never. */
    val SESSION_FEATURES: Set<String> = linkedSetOf(
        CarPlayVehicleState.FEATURE,
        "uiSync",
        "uiContext",
        "sessionManagement",
        "fileTransfer",
    )

    /**
     * Enabled unless overridden: the vehicle-state protocol, which Tarmac answers ([CarPlayVehicleState]). The
     * others end the session without their `/info` entries (docs/CARPLAY_ULTRA.md); LIVI enables `uiSync`
     * only with a car maker's theme.
     */
    val DEFAULT_FEATURES: Set<String> = linkedSetOf(CarPlayVehicleState.FEATURE)

    /**
     * The features to enable from a comma-separated override (an adb-editable preference for
     * experiments), or [DEFAULT_FEATURES] when there is none. Only names from [SESSION_FEATURES] count.
     */
    fun features(override: String?): Set<String> {
        val names = override?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return DEFAULT_FEATURES
        return names.filterTo(linkedSetOf()) { it in SESSION_FEATURES }
    }

    /** At most this many packages per probed stream are logged in full; later ones only every [LOG_EVERY]. */
    const val LOGGED_PACKAGES = 200
    const val LOG_EVERY = 100
    private const val SUMMARY_CHARS = 800

    /** One line for a data-stream package: header fields and the body as a plist when it decodes as one. */
    fun describePackage(type: String, command: Int, sequence: Long, body: ByteArray): String {
        val content = runCatching { BplistCodec.decode(body).toString() }.getOrNull()
            ?: body.take(64).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "type=$type command=0x${command.toUInt().toString(16)} seq=$sequence body=${body.size}B " +
            content.take(SUMMARY_CHARS)
    }
}
