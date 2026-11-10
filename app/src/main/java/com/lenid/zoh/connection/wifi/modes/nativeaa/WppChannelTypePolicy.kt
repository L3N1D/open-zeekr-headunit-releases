package com.lenid.zoh.connection.wifi.modes.nativeaa

import com.lenid.zoh.aap.protocol.proto.Wireless

/**
 * Which bands we tell the phone our access point can offer, in WifiVersionRequest field 3.
 *
 * Android Auto answers STATUS_NO_SUPPORTED_WIFI_CHANNELS (-8) when it cannot match our bands, and
 * an unreadable radio must send nothing rather than guess: a wrong claim is worse than silence.
 */
object WppChannelTypePolicy {

    /** [supports5Ghz] is null when the WiFi service could not be asked. */
    fun forHeadUnit(supports5Ghz: Boolean?): Wireless.WifiChannelType? = when (supports5Ghz) {
        // Every Android device that does 5 GHz also does 2.4, so this is never 5 GHz only.
        true -> Wireless.WifiChannelType.CHANNELS_DUAL_BAND
        false -> Wireless.WifiChannelType.CHANNELS_24GHZ_ONLY
        null -> null
    }
}
