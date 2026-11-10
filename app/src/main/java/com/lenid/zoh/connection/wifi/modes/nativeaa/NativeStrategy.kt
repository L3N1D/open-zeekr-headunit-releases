package com.lenid.zoh.connection.wifi.modes.nativeaa

/** Which network the Native AA mode puts the phone on. */
enum class NativeStrategy(val id: Int) {

    /** A WiFi Direct P2P group with this head unit as group owner. The only transport on the Zeekr. */
    WIFI_DIRECT(0);

    companion object {

        val DEFAULT: NativeStrategy = WIFI_DIRECT

        fun byIdOrDefault(id: Int): NativeStrategy {
            for (strategy in NativeStrategy.entries) {
                if (strategy.id == id)
                    return strategy
            }
            return DEFAULT
        }

        fun fromSetting(setting: Int): NativeStrategy = byIdOrDefault(setting)
    }
}

typealias NativeTransport = NativeStrategy
