package com.lenid.zoh.main

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.lenid.zoh.App
import com.lenid.zoh.R
import androidx.lifecycle.MutableLiveData
import com.lenid.zoh.BuildConfig
import com.lenid.zoh.utils.ToastUtils
import com.lenid.zoh.utils.UpdateChecker
import com.lenid.zoh.utils.UpdateInfo
import com.lenid.zoh.utils.UpdateInstaller
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UpdatePrompt {

    private val availability = MutableLiveData<Boolean>()
    private var checking = false
    private var lastAttempt: Long? = null

    fun observeAvailability(context: Context, owner: androidx.lifecycle.LifecycleOwner, render: (Boolean) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        render(prefs.getString("update_for_version", null) == BuildConfig.VERSION_NAME)
        availability.observe(owner) { render(it) }
    }

    private const val PREFS = "updates"
    private const val AT_START = "check_at_start"
    private const val LAST_CHECK = "last_check_ms"

    fun checkAtStartEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AT_START, true)

    fun setCheckAtStart(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AT_START, enabled).apply()

    /** When GitHub last answered a check, 0 if never. */
    fun lastCheck(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_CHECK, 0L)

    fun noteChecked(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_CHECK, System.currentTimeMillis())
            .putString("update_for_version", if (info.isUpdateAvailable) BuildConfig.VERSION_NAME else null)
            .apply()
        availability.value = info.isUpdateAvailable
    }

    suspend fun checkAutomatically(activity: Activity) {
        if (checking || !checkAtStartEnabled(activity) || UpdateChecker.isPlayStoreInstallation(activity)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastAttempt?.let { now - it < 60_000L } == true) return
        lastAttempt = now
        checking = true
        try {
            UpdateChecker.check(activity).onSuccess { noteChecked(activity, it) }
        } finally {
            checking = false
        }
    }

    fun show(activity: Activity, info: UpdateInfo, scope: CoroutineScope) {
        val builder = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_install_message, info.latestVersionName))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.open_github_releases) { _, _ ->
                UpdateChecker.openGitHubReleases(activity, info.releaseUrl)
            }
        val apkUrl = info.apkUrl
        if (apkUrl != null) {
            builder.setPositiveButton(R.string.update_install) { _, _ -> downloadAndInstall(activity, apkUrl, scope) }
        }
        builder.show()
    }

    private fun downloadAndInstall(activity: Activity, url: String, scope: CoroutineScope) {
        if (App.provide(activity).commManager.isConnected) {
            ToastUtils.showToast(activity, R.string.update_wait_disconnect, Toast.LENGTH_LONG, force = true)
            return
        }
        if (!UpdateInstaller.canInstall(activity)) {
            // One-time: Android asks the user to let this app install updates.
            ToastUtils.showToast(activity, R.string.update_install_permission, Toast.LENGTH_LONG, force = true)
            UpdateInstaller.openInstallPermission(activity)
            return
        }
        val progress = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.update_downloading)
            .setMessage("0 %")
            .setCancelable(false)
            .show()
        scope.launch {
            val result = UpdateInstaller.download(activity, url) { p ->
                activity.runOnUiThread { progress.setMessage("$p %") }
            }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                result.fold(
                    onSuccess = { apk -> UpdateInstaller.install(activity, apk) },
                    onFailure = {
                        ToastUtils.showToast(activity, R.string.update_download_failed, Toast.LENGTH_LONG, force = true)
                    }
                )
            }
        }
    }
}
