package com.lenid.zoh.connection.wifi

/**
 * Whether the wireless stack may arm itself when the user never chose a wireless connection.
 *
 * "Connection mode" used to decide only which settings were shown, so a cable-only unit still
 * created a P2P group and poked phones. A tap on the WiFi button is still a wireless connection the
 * user asked for, so it passes.
 */
object WirelessSelectionPolicy {

    fun refusesBringUp(wirelessSelected: Boolean, userRequested: Boolean): Boolean =
        !wirelessSelected && !userRequested
}
