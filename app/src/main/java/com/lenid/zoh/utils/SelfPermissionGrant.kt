package com.lenid.zoh.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat

/** Optional platform-signed firmware support. No shell, global settings or other-package API. */
internal object SelfPermissionGrant {
    fun apply(context: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        val own = context.applicationContext
        val packageName = own.packageName
        val uid = Process.myUid()
        if (own.applicationInfo.uid != uid) return

        if (granted(own, "android.permission.GRANT_RUNTIME_PERMISSIONS")) {
            // Coarse location precedes fine location on Android versions that require both.
            val missing = AppPermissions.missingNormalPermissions(own)
                .sortedBy { if (it == android.Manifest.permission.ACCESS_COARSE_LOCATION) 0 else 1 }
            for (permission in missing) {
                try {
                    own.packageManager.javaClass.getMethod(
                        "grantRuntimePermission", String::class.java, String::class.java,
                        android.os.UserHandle::class.java,
                    ).invoke(own.packageManager, packageName, permission, Process.myUserHandle())
                    AppLog.i("SelfPermissionGrant: $permission granted=${granted(own, permission)}")
                } catch (e: Exception) {
                    AppLog.w("SelfPermissionGrant: $permission requires normal request (${e.cause ?: e})")
                }
            }
        }

        if (!granted(own, "android.permission.MANAGE_APP_OPS_MODES")) return
        val ops = own.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return
        val required = linkedMapOf(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW to { AppPermissions.isOverlayGranted(own) },
            AppOpsManager.OPSTR_WRITE_SETTINGS to { AppPermissions.isWriteSettingsGranted(own) },
        )
        if (Build.VERSION.SDK_INT >= 26) {
            required["android:request_install_packages"] = {
                own.packageManager.canRequestPackageInstalls()
            }
        }
        for ((op, available) in required) {
            try {
                if (available()) continue
                ops.javaClass.getMethod("setMode", String::class.java, Int::class.javaPrimitiveType,
                    String::class.java, Int::class.javaPrimitiveType)
                    .invoke(ops, op, uid, packageName, AppOpsManager.MODE_ALLOWED)
                AppLog.i("SelfPermissionGrant: $op available=${available()}")
            } catch (e: Exception) {
                AppLog.w("SelfPermissionGrant: $op requires normal request (${e.cause ?: e})")
            }
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
