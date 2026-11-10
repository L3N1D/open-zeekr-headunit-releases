package com.lenid.zoh.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lenid.zoh.App
import com.lenid.zoh.aap.AapProjectionActivity
import com.lenid.zoh.automation.AutomationCommandPolicy
import com.lenid.zoh.automation.AutomationEffectRunner
import com.lenid.zoh.automation.IntentExtras
import com.lenid.zoh.contract.HeadUnitCommand
import com.lenid.zoh.utils.AppLog
import org.json.JSONObject

/**
 * Handles App Shortcuts and `headunit://` deep links, sharing every decision and action with
 * `AutomationReceiver`. It exists because those can only target an activity, and because the Self
 * Mode pre-launch below needs a foreground window. Prefer the receiver where either will do.
 */
class AutomationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Invisible activity
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val data = intent.data
        AppLog.i("AutomationActivity: Received intent. Action: ${intent.action}, Data: $data")

        val action = if (data?.scheme == "headunit") actionFor(data.host) else intent.action
        if (action != null) {
            runCommand(action, if (data?.scheme == "headunit") queryParameters(data) else emptyMap())
        }

        finish()
    }

    /** `headunit://connect?ip=…`, `disconnect`, `exit`, `nightmode?state=…`. */
    private fun actionFor(host: String?): String? = when (host) {
        "connect" -> HeadUnitCommand.ACTION_CONNECT
        "disconnect" -> HeadUnitCommand.ACTION_DISCONNECT
        "exit" -> HeadUnitCommand.ACTION_STOP_SERVICE
        "nightmode" -> HeadUnitCommand.ACTION_SET_NIGHT_MODE
        else -> null
    }

    /**
     * A deep link carries arguments as query parameters, not extras, so they go to [IntentExtras] as
     * overrides. Passing all of them keeps this door accepting exactly what the receiver does.
     */
    private fun queryParameters(data: Uri): Map<String, String> =
        data.queryParameterNames.mapNotNull { name ->
            data.getQueryParameter(name)?.let { name to it }
        }.toMap()

    private fun runCommand(action: String, overrides: Map<String, String>) {
        val effects = AutomationCommandPolicy.effectsFor(
            action,
            IntentExtras(intent, overrides),
            AutomationCommandPolicy.State(
                externalConfigAllowed = App.provide(this).settings.allowExternalConfiguration
            )
        )
        AutomationEffectRunner.run(this, effects, JSONObject())

    }
}
