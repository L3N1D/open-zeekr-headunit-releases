package com.lenid.zoh.connection.wifi.modes.nativeaa

/**
 * The channel list in WifiVersionRequest field 4: the one channel our group runs on.
 *
 * The phone checks it against the 5 GHz DFS channels it can scan, so a 2.4 GHz group still gets
 * -8 by design. A value that names no real channel passes the check and describes nothing.
 */
object WppChannelListPolicy {
    private const val MIN_FREQUENCY_MHZ = 2400
    private const val UNREAL_FREQUENCY_MHZ = 5000

    /** [frequencyMhz] is 0 when unreadable: below API 29, or a transport with no P2P group. */
    fun forGroup(frequencyMhz: Int): List<Int> =
        if (frequencyMhz < MIN_FREQUENCY_MHZ || frequencyMhz == UNREAL_FREQUENCY_MHZ) emptyList()
        else listOf(frequencyMhz)
}
