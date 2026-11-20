package com.lenid.zoh.main

import android.content.Context
import android.net.Uri
import com.lenid.zoh.BuildConfig
import com.lenid.zoh.R

internal object CompanionCompatibility {
    fun report(context: Context): String = buildString {
        appendLine("Open Headunit: ${BuildConfig.VERSION_NAME}")
        val version = runCatching { context.packageManager.getPackageInfo(HudMapLauncher.PACKAGE, 0).versionName }.getOrNull()
        appendLine("HUD Map: ${version ?: context.getString(R.string.support_missing)}")
        val protocol = runCatching {
            context.contentResolver.query(Uri.parse("content://com.lenid.ohroutemap.settings/compatibility"), null, null, null, null)?.use { cursor ->
                if(cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow("call_protocol")) else null
            }
        }.getOrNull()
        appendLine(context.getString(if(protocol == 1) R.string.support_protocol_ok else R.string.support_protocol_unknown))
        appendLine(context.getString(if(android.provider.Settings.canDrawOverlays(context)) R.string.support_overlay_ok else R.string.support_overlay_missing))
        appendLine(context.getString(R.string.support_compatibility_note))
        append(DiagnosticSnapshot.status(context))
    }
}
