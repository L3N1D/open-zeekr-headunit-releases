package com.lenid.zoh.connection.wifi.direct

/** Whether this unit leaves its own WiFi network for the Native AA WiFi Direct bring-up. */
enum class StationStandDownMode {
    /** Only where the group can gain a band: a 5 GHz radio whose station sits on 2.4 GHz. The default. */
    AUTO,

    /** Every bring-up the platform allows, which is what 3.4.0-beta1 did unconditionally. */
    ALWAYS,

    /** Never; the station stays joined and the group shares the radio with it. */
    NEVER;

    companion object {
        /** [Settings.stationStandDownMode][com.lenid.zoh.utils.Settings.stationStandDownMode]
         *  as a mode, defaulting to [AUTO] for any value we do not recognise. */
        fun fromSetting(value: Int): StationStandDownMode = when (value) {
            1 -> ALWAYS
            2 -> NEVER
            else -> AUTO
        }
    }
}
