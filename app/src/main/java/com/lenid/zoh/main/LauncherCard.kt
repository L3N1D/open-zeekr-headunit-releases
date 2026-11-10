package com.lenid.zoh.main

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import com.lenid.zoh.utils.AppLog

/**
 * The navigator card on the Zeekr home screen. ecarx.launcher3 runs whatever package the global
 * setting [KEY] names inside a window of its own (a private virtual display, 1275x876 on the
 * car), which is how Yandex Navigator gets there; GMC's "navigation app" choice writes the same
 * setting. Naming us there puts Android Auto in the card, and the projection then announces the
 * card's shape to the phone like any other window.
 *
 * Writing the setting needs WRITE_SECURE_SETTINGS, held with the platform signature.
 */
object LauncherCard {

    private const val KEY = "navi_on_launcher"
    private const val PREFS = "launcher_card"
    private const val PREVIOUS = "previous_navigator"
    private const val EXPAND_KEY = "key_launcher_cards_container_expand_state"

    /** Whether [activity] runs inside the card rather than on the car's screen. */
    fun isOnCard(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val id = activity.display?.displayId ?: return false
        return id != Display.DEFAULT_DISPLAY
    }

    /** Options that keep a started activity in the card when [activity] is in it; null elsewhere. */
    fun launchOptions(activity: Activity): Bundle? {
        if (!isOnCard(activity)) return null
        val id = activity.display?.displayId ?: return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
    }

    /**
     * Where the card sits on the car's screen. The launcher's side widgets decide its width
     * (system setting [EXPAND_KEY]: 1 out, 0 in); measured on the car, 2026-10-31.
     */
    fun boundsOnScreen(context: Context): android.graphics.Rect? {
        val expanded = try {
            android.provider.Settings.System.getInt(context.contentResolver, EXPAND_KEY)
        } catch (_: Exception) { return null }
        return if (expanded == 1) android.graphics.Rect(644, 92, 1920, 968)
        else android.graphics.Rect(124, 92, 1920, 968)
    }

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /**
     * MyRegion (com.logstas.myregion) rewrites the navi keys from its own "navigation app"
     * setting, which takes the card away from whatever the owner picked here. Its "Nothing"
     * choice is the only one that leaves the keys alone, so with it installed the owner needs
     * a heads-up rather than a silent fight over the setting.
     */
    fun hasConflictingNavigatorApp(context: Context): Boolean = try {
        context.packageManager.getPackageInfo("com.logstas.myregion", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isEnabled(context: Context): Boolean =
        android.provider.Settings.Global.getString(context.contentResolver, KEY) == context.packageName

    /**
     * The keys GMC writes when it picks the card's navigator. The launcher only switched apps once
     * all of them named the new one (navi_on_launcher alone was ignored). stop_navi_after_launcher
     * is left alone: it names an app the launcher stops on leaving the home screen, which for us
     * would end Android Auto.
     */
    private val NAVI_KEYS = listOf(KEY, "navi_package", "navi_on_launcher_old")
    private const val ACTIVITY_KEY = "navi_activity"
    private const val WANTED = "card_wanted"

    /** Puts us in the card, remembering the navigator it showed, or gives the card back to it. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!canWrite(context)) return false
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            if (enabled) {
                val current = android.provider.Settings.Global.getString(resolver, KEY)
                if (current != null && current != context.packageName) {
                    prefs.edit().putString(PREVIOUS, current)
                        .putString(PREVIOUS + "_activity", android.provider.Settings.Global.getString(resolver, ACTIVITY_KEY))
                        .apply()
                }
                writeKeys(context, context.packageName, LauncherEntryActivity::class.java.name)
            } else if (isEnabled(context)) {
                writeKeys(context, prefs.getString(PREVIOUS, null), prefs.getString(PREVIOUS + "_activity", null))
            }
            prefs.edit().putBoolean(WANTED, enabled).apply()
            AppLog.i("LauncherCard: home screen card ${if (enabled) "shows Android Auto" else "given back"}")
            true
        } catch (e: Exception) {
            AppLog.w("LauncherCard: could not write $KEY: ${e.message}")
            false
        }
    }

    private fun writeKeys(context: Context, pkg: String?, activity: String?) {
        val resolver = context.contentResolver
        for (k in NAVI_KEYS) android.provider.Settings.Global.putString(resolver, k, pkg)
        android.provider.Settings.Global.putString(resolver, ACTIVITY_KEY, activity)
    }

    /**
     * Called when the app starts: the keys do not survive a reboot (GMC used to write them at
     * boot, and with it turned off they came back empty), so the choice is written again.
     */
    fun reassert(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Put there before this switch existed (by hand or through GMC): that is the choice.
        if (!prefs.contains(WANTED) && isEnabled(context)) prefs.edit().putBoolean(WANTED, true).apply()
        val wanted = prefs.getBoolean(WANTED, false)
        if (!wanted || !canWrite(context)) return
        val resolver = context.contentResolver
        if (NAVI_KEYS.all { android.provider.Settings.Global.getString(resolver, it) == context.packageName } &&
            android.provider.Settings.Global.getString(resolver, ACTIVITY_KEY) == LauncherEntryActivity::class.java.name) return
        writeKeys(context, context.packageName, LauncherEntryActivity::class.java.name)
        AppLog.i("LauncherCard: home screen card reset since the last start, put Android Auto back in it")
    }
}
