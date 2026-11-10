package com.lenid.zoh.main

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.lenid.zoh.App
import com.lenid.zoh.aap.AapProjectionActivity
import com.lenid.zoh.utils.AppLog

/**
 * The app's launcher entry: a see-through step in front of [MainActivity].
 *
 * The Zeekr home screen starts the card's app again every time it comes back (after the app
 * list, Home, any app closed over it), always with a fresh activity. When that was MainActivity,
 * its window covered the running projection for a moment: the projection lost its surface, the
 * video decoder restarted and the card flickered. This window is transparent, so a projection
 * already showing in the same window stays drawn; it is then left alone. Anything else goes on
 * to MainActivity as before.
 */
class LauncherEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionHere = App.provide(this).commManager.isConnected && !App.isPiPActive &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            AapProjectionActivity.liveWindow.isBehind(this)
        if (projectionHere) {
            AppLog.i("LauncherEntryActivity: the projection is already showing here, leaving it")
        } else {
            startActivity(Intent(intent).setClass(this, MainActivity::class.java), LauncherCard.launchOptions(this))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
