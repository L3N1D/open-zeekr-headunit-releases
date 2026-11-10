package com.lenid.zoh.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * TEMPORARY diagnostic: logs every extra of the broadcasts the GMC mod uses to feed the HUD and
 * cluster, so the format can be mirrored from Android Auto guidance. Remove once it is known.
 */
internal object GmcFeedProbe {

    private val ACTIONS = listOf(
        "com.frida.injector.SEND",
        "gmc.ecarx.xsf.hud.update",
        "gmc.ecarx.xsf.hud.progress",
    )

    fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extras = intent.extras
                val fields = extras?.keySet()?.joinToString(", ") { key ->
                    @Suppress("DEPRECATION")
                    val value = extras.get(key)
                    "$key=(${value?.javaClass?.simpleName})$value"
                } ?: "no extras"
                android.util.Log.i("GMCPROBE", "${intent.action}: $fields")
            }
        }
        val filter = IntentFilter().apply { ACTIONS.forEach { addAction(it) } }
        context.registerReceiver(receiver, filter)
    }
}
