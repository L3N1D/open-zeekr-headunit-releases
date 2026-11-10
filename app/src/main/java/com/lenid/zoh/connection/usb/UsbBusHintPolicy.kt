package com.lenid.zoh.connection.usb

/**
 * Which plain-English hint a USB scan should end with.
 *
 * The dump used to explain itself only when the bus was empty, so the case that actually reaches the
 * tracker - devices present, none of them a phone - said nothing. A wireless adapter that has not
 * found its phone re-enumerates under a different identity every half minute, which is the one shape
 * worth naming on sight.
 */
object UsbBusHintPolicy {

    /** How far back distinct identities are counted. */
    const val IDENTITY_WINDOW_MS = 60_000L

    /**
     * Changes of the bus's whole composition inside the window that mean a device is cycling.
     *
     * Counting coexisting identities instead read a permanent hub plus a built-in LTE modem as a
     * re-enumerating dongle on every scan, and fired again mid-AOA-switch. One plug-in or one
     * switch moves the composition at most twice, so the threshold sits above that.
     */
    const val CYCLING_CHANGES = 3

    enum class Hint { NO_HOST_SUPPORT, EMPTY_BUS, CYCLING_ADAPTER, NONE_USABLE }

    /**
     * @param featureDeclared `android.hardware.usb.host`. A ROM that omits it never starts the
     *   framework's host stack, so the bus stays empty whatever is plugged in - a permanent fact
     *   about the unit rather than the three-way guess [Hint.EMPTY_BUS] offers.
     * @param compositionChangesInWindow from [compositionChanges]: how many times the set of
     *   identities on the bus changed, never how many devices are on it at once.
     */
    fun hint(
        deviceCount: Int,
        acceptedCount: Int,
        compositionChangesInWindow: Int,
        featureDeclared: Boolean,
    ): Hint? = when {
        // Only when nothing enumerated: some ROMs omit the declaration and host devices anyway, and
        // telling those users their unit cannot do USB would be worse than saying nothing.
        deviceCount == 0 && !featureDeclared -> Hint.NO_HOST_SUPPORT
        deviceCount == 0 -> Hint.EMPTY_BUS
        acceptedCount > 0 -> null
        compositionChangesInWindow >= CYCLING_CHANGES -> Hint.CYCLING_ADAPTER
        else -> Hint.NONE_USABLE
    }

    /**
     * How many times the bus looked different from the scan before it, inside the window.
     *
     * A bus whose devices never change scores 0 however many of them there are, which is the whole
     * point: only something arriving or leaving moves this.
     */
    fun compositionChanges(scans: List<Pair<Long, Set<String>>>, nowMs: Long): Int {
        val inWindow = scans.filter { nowMs - it.first < IDENTITY_WINDOW_MS }.sortedBy { it.first }
        return inWindow.zipWithNext().count { (before, after) -> before.second != after.second }
    }

    /** Identities seen strictly inside the window, newest first, deduplicated. */
    fun identitiesInWindow(seen: List<Pair<Long, String>>, nowMs: Long): List<String> =
        seen.filter { nowMs - it.first < IDENTITY_WINDOW_MS }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinct()
}
