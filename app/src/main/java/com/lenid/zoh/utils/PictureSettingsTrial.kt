package com.lenid.zoh.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Persist the old values before applying a trial, including absence (legacy fallback). */
object PictureSettingsTrial {
    private val main = Handler(Looper.getMainLooper())
    private var rollback: Runnable? = null
    private var restoring = false
    private val keys = setOf("resolutionId", "dpi-pixel-density", "pixel-aspect-ratio-e4", "fps-limit", "video-profile-starvation-cap")
    private fun state(c: Context) = c.getSharedPreferences("picture_settings_trial", 0)
    fun pending(c: Context) = state(c).contains("before")
    fun relevant(key: String): Boolean = key.replace(Regex("^display_[0-9]+_"), "") in keys
    fun begin(c: Context) {
        if (restoring) return
        val app = c.applicationContext
        if (!pending(app)) {
            val values = JSONObject()
            val preferences = app.getSharedPreferences("settings", 0)
            val names = if (false) keys.flatMap { listOf(it, "display_${preferences.getInt("display", 2)}_$it") } else keys.toList()
            for (key in names) values.put(key, preferences.all[key] ?: JSONObject.NULL)
            check(state(app).edit().putString("before", values.toString()).commit())
        }
        rollback?.let(main::removeCallbacks)
        rollback = Runnable { restore(app) }.also { main.postDelayed(it, 60_000) }
    }
    fun keep(c: Context) {
        rollback?.let(main::removeCallbacks); rollback = null
        state(c).edit().clear().commit()
    }
    fun restore(c: Context) {
        val saved = state(c).getString("before", null) ?: return
        restoring = true
        try {
            val values = JSONObject(saved)
            val editor = c.getSharedPreferences("settings", 0).edit()
            for (key in values.keys()) {
                if(values.isNull(key)) editor.remove(key) else when(val value = values.get(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                }
            }
            editor.commit()
            keep(c)
        } finally { restoring = false }
    }
}
