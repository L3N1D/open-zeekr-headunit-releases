package com.lenid.zoh.utils

import android.content.*
import android.os.*
import androidx.core.content.ContextCompat
import com.lenid.zoh.App

/** Optional one-shot after wake. Existing connection watchdog remains the owner of recovery. */
object WakePictureCheck {
    fun register(context: Context) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val check = Runnable {
            val component = App.provide(app)
            val d = component.videoDecoder
            val now = SystemClock.elapsedRealtime()
            if (app.getSharedPreferences("settings", 0).getBoolean("support-wake-picture", false) &&
                app.getSystemService(PowerManager::class.java)?.isInteractive == true && component.commManager.isConnected &&
                d.hasPictureSurface() && WakePicturePolicy.shouldRequest(now, d.lastInputBytesReceivedMs, d.lastFrameRenderedMs)) {
                PictureRecovery.request(app)
            }
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                main.removeCallbacks(check)
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    ConnectionJournal.record(app, "screen:awake")
                    main.postDelayed(check, 10_000)
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
internal object WakePicturePolicy {
    fun shouldRequest(now: Long, input: Long, rendered: Long) =
        input > 0 && rendered > 0 && now >= input && now >= rendered && now - input < 2_000 && now - rendered > 8_000
}
