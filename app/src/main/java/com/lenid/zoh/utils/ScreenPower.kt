package com.lenid.zoh.utils

import android.content.Context
import android.os.Build
import android.os.PowerManager

object ScreenPower {

    /** True when the screen is lit, null when it cannot be read. */
    fun isInteractive(context: Context): Boolean? = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
    } catch (e: Exception) {
        null
    }
}
