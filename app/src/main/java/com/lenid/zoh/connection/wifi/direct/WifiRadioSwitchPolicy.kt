package com.lenid.zoh.connection.wifi.direct

import android.os.Build

/**
 * Whether this unit's WiFi radio is off in a way nothing the app does will change.
 *
 * A group needs the radio on, and from API 29 `setWifiEnabled` is refused, so on such a unit every
 * create, every credential refresh and every hotspot teardown taken on the group's behalf is spent
 * on a group that cannot form. Pure, so both callers ask the same question.
 */
object WifiRadioSwitchPolicy {

    /** Whether this Android still lets an app switch the radio on for itself. */
    fun appCanEnable(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.Q

    /** Off, and only the user can change that. */
    fun isBlockedOff(wifiEnabled: Boolean, sdkInt: Int): Boolean =
        !wifiEnabled && !appCanEnable(sdkInt)
}
