package com.lenid.zoh.utils

import android.content.Context
import android.os.SystemClock
import com.lenid.zoh.App
import com.lenid.zoh.carplay.DirectCarPlayService

/** Gain-only picture request. Does not stop a codec, a session or change the window. */
object PictureRecovery {
    private var lastRequest = -10_000L
    @Synchronized fun request(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRequest < 5_000) return false
        val app = App.provide(context)
        val decoder = app.videoDecoder
        val accepted = if (app.commManager.isConnected && decoder.hasPictureSurface()) {
            decoder.onFrameDropped?.let { it(); true } ?: false
        } else DirectCarPlayService.refreshCurrentPicture()
        if (accepted) { lastRequest = now; ConnectionJournal.record(context, "picture:requested") }
        return accepted
    }
}
