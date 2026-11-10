package com.lenid.zoh.connection.wifi

import com.lenid.zoh.connection.wifi.modes.WifiLauncherNative

enum class WifiLauncherMode(
    val id: Int,
    val factory: (WifiLauncherManager) -> WifiLauncher) {

    // The phone's own wireless Android Auto is the only way in on the Zeekr. Ids 0-2 (Headunit
    // Server manual and auto, Wireless Helper) were removed; a stored one falls back to this.
    NATIVE(3, ::WifiLauncherNative);

    companion object {

        val DEFAULT: WifiLauncherMode = NATIVE


        fun byIdOrDefault(id: Int): WifiLauncherMode {
            for (mode in WifiLauncherMode.entries) {
                if (mode.id == id)
                    return mode
            }

            return DEFAULT
        }
    }
}
