package com.lenid.zoh.main

import android.app.Activity
import android.widget.Toast
import com.lenid.zoh.R
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.ToastUtils
import com.lenid.zoh.utils.UpdateInstaller
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home's "HUD map" tile: opens Zeekr HUD Map, or, when it is not on the unit, offers to fetch
 * the latest release from its repository and hand it to the system installer.
 */
object HudMapLauncher {
    const val PACKAGE = "com.lenid.ohroutemap"
    private const val LATEST_APK =
        "https://github.com/L3N1D/zeekr-hud-map-releases/releases/latest/download/zeekr-hud-map.apk"

    fun open(activity: Activity, scope: CoroutineScope) {
        val launch = activity.packageManager.getLaunchIntentForPackage(PACKAGE)
        if (launch != null) {
            activity.startActivity(launch)
            return
        }
        MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.hud_map_missing_title)
            .setMessage(R.string.hud_map_missing_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.hud_map_install) { _, _ -> install(activity, scope) }
            .show()
    }

    private fun install(activity: Activity, scope: CoroutineScope) {
        if (!UpdateInstaller.canInstall(activity)) {
            // One-time: Android asks the user to let this app install others.
            ToastUtils.showToast(activity, R.string.update_install_permission, Toast.LENGTH_LONG, force = true)
            UpdateInstaller.openInstallPermission(activity)
            return
        }
        val progress = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(R.string.hud_map_downloading)
            .setMessage("0 %")
            .setCancelable(false)
            .show()
        scope.launch {
            val result = UpdateInstaller.download(activity, LATEST_APK) { p ->
                activity.runOnUiThread { progress.setMessage("$p %") }
            }
            withContext(Dispatchers.Main) {
                progress.dismiss()
                result.fold(
                    onSuccess = { apk -> UpdateInstaller.install(activity, apk) },
                    onFailure = {
                        AppLog.w("HudMapLauncher: download failed: ${it.message}")
                        ToastUtils.showToast(activity, R.string.update_download_failed, Toast.LENGTH_LONG, force = true)
                    }
                )
            }
        }
    }
}
