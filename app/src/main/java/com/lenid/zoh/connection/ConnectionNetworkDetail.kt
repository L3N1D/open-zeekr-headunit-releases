package com.lenid.zoh.connection

/**
 * What the status pill's third line says about the WiFi Direct group, beside the stage.
 *
 * Used to be three toasts, which drew bottom-centre over the pill for 7 to 8 s per bring-up.
 * [frequencyMhz] is 0 when the platform does not report it (WifiP2pGroup carries it from API 29).
 */
data class ConnectionNetworkDetail(val frequencyMhz: Int, val note: Note = Note.NONE) {
    enum class Note { NONE, RETRYING_5GHZ, CLIENT_UNFRIENDLY_CHANNEL }
}
