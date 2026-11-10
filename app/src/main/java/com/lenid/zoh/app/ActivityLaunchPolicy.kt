package com.lenid.zoh.app

import android.os.Build

object ActivityLaunchPolicy {

    enum class LaunchStrategy { DIRECT, OVERLAY, NOTIFICATION }

    /**
     * Chooses the safest activity-start strategy for the given API level.
     *
     * On Android 10+ (API 29+), direct startActivity() from a background Service is
     * silently blocked by the OS. The overlay trampoline bypasses this if the
     * SYSTEM_ALERT_WINDOW permission is granted; otherwise a full-screen notification
     * is used as fallback.
     *
     * [appIsForeground] is the exception the restriction itself carries: an app with a visible
     * activity may start another one. Without it, a unit with the overlay off fell to a
     * notification nobody tapped while the home screen was on the panel, and the phone waited on a
     * handshake that never became a picture.
     */
    fun chooseLaunchStrategy(
        apiLevel: Int,
        canDrawOverlays: Boolean,
        appIsForeground: Boolean = false,
    ): LaunchStrategy = when {
        apiLevel < Build.VERSION_CODES.Q -> LaunchStrategy.DIRECT
        appIsForeground -> LaunchStrategy.DIRECT
        canDrawOverlays -> LaunchStrategy.OVERLAY
        else -> LaunchStrategy.NOTIFICATION
    }
}
