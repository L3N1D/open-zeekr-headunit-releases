package com.lenid.zoh.aap

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lenid.zoh.utils.AppLog

/**
 * Stops the head unit's own Yandex Navigator while Android Auto runs and the navigator is not on
 * screen. Navigation then comes from the phone, and the built-in one kept up to two of the 8155's
 * cores and 600 MB busy in the background (measured on the car). Once it has been out of sight for
 * [HIDDEN_MS], it is force-stopped; if something starts it again, it is stopped again.
 *
 * Needs the platform signature (FORCE_STOP_PACKAGES, and REAL_GET_TASKS to see other apps'
 * processes); without it this does nothing. Main thread only.
 */
internal class BackgroundNaviReaper(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var hiddenSinceMs = 0L
    private var running = false

    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick() } catch (e: Exception) { AppLog.w("BackgroundNaviReaper: ${e.message}") }
            main.postDelayed(this, CHECK_MS)
        }
    }

    fun start() {
        if (running) return
        if (context.checkSelfPermission(FORCE_STOP) != PackageManager.PERMISSION_GRANTED) return
        running = true
        hiddenSinceMs = 0L
        main.post(check)
    }

    fun stop() {
        running = false
        main.removeCallbacks(check)
    }

    private fun tick() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val processes = am.runningAppProcesses?.filter { p -> p.pkgList?.contains(PACKAGE) == true }.orEmpty()
        if (processes.isEmpty()) { hiddenSinceMs = 0L; return }
        // Visible covers the launcher's map card too, which shows the navigator on a virtual display.
        val shown = processes.any { it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE }
        val now = SystemClock.elapsedRealtime()
        if (shown) { hiddenSinceMs = 0L; return }
        if (hiddenSinceMs == 0L) { hiddenSinceMs = now; return }
        if (now - hiddenSinceMs < HIDDEN_MS) return
        ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(am, PACKAGE)
        AppLog.i("BackgroundNaviReaper: stopped $PACKAGE, hidden for ${(now - hiddenSinceMs) / 1000}s during Android Auto")
        hiddenSinceMs = 0L
    }

    companion object {
        const val PACKAGE = "ru.yandex.yandexnavi"
        private const val FORCE_STOP = "android.permission.FORCE_STOP_PACKAGES"
        private const val CHECK_MS = 30_000L
        private const val HIDDEN_MS = 60_000L
    }
}
