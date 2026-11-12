package com.lenid.zoh.carplay

import android.content.Context
import com.lenid.zoh.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.util.ArrayDeque

/** Contains stage names and classified errors only: never phone identifiers, credentials or protocol payloads. */
internal object CarPlayDiagnostics {
    private val events = ArrayDeque<String>()
    private var lastStage = "Idle"
    @Synchronized fun record(state: CarPlayStatus) {
        val stage = state.javaClass.simpleName
        val detail = if (state is CarPlayStatus.Failed) " after=$lastStage cause=${failure(state)}" else ""
        if (state !is CarPlayStatus.Failed) lastStage = stage
        events.addLast("${System.currentTimeMillis()} $stage$detail")
        while (events.size > 100) events.removeFirst()
    }
    @Synchronized fun begin(wireless: Boolean, ultra: Boolean) {
        events.clear(); lastStage = "Starting"
        events.addLast("transport=${if (wireless) "Wi-Fi" else "USB"} ultra=$ultra")
    }
    @Synchronized fun report() = "Open Zeekr Headunit CarPlay ${com.lenid.zoh.BuildConfig.VERSION_NAME}\n" +
        "Stage-only report; timestamps are Unix milliseconds. No protocol trace or phone identifiers.\n" + events.joinToString("\n")

    fun failure(state: CarPlayStatus.Failed): String = when {
        state.wifiResetRequired -> "WIFI_RESET"
        state.knownCause != null -> state.knownCause?.name ?: "CONNECTION"
        state.startupFailure != null -> "BLUETOOTH_OR_WIFI"
        state.message.contains("Pipe error", true) || state.message.contains("Input/Output", true) -> "USB_IO"
        state.message.contains("timed out", true) || state.message.contains("timeout", true) -> "TIMEOUT"
        else -> "CONNECTION"
    }
    fun message(state: CarPlayStatus): Int = when (state) {
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady -> R.string.carplay_stage_auth
        CarPlayStatus.WaitingForMfi -> R.string.carplay_auth_missing
        CarPlayStatus.RequestingMfiPermission, CarPlayStatus.RequestingIphonePermission -> R.string.carplay_stage_permission
        CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady -> R.string.carplay_stage_wifi
        CarPlayStatus.WaitingForPairedIphone -> R.string.carplay_stage_pair_phone
        CarPlayStatus.ConnectingBluetooth -> R.string.carplay_stage_bluetooth
        CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone -> R.string.carplay_stage_usb
        is CarPlayStatus.IphoneNotListed -> R.string.carplay_stage_usb_missing
        CarPlayStatus.WaitingForReenumeration, CarPlayStatus.SelectingConfiguration -> R.string.carplay_stage_usb_mode
        CarPlayStatus.OpeningDataPaths -> R.string.carplay_stage_transport
        CarPlayStatus.Pairing -> R.string.carplay_stage_pairing
        CarPlayStatus.ConnectingControl, CarPlayStatus.RunningControl -> R.string.carplay_stage_control
        CarPlayStatus.AttachingNetwork -> R.string.carplay_stage_network
        CarPlayStatus.ControlEnded -> R.string.carplay_stage_ended
        is CarPlayStatus.Failed -> when (failure(state)) {
            "USB_IO" -> R.string.carplay_error_usb
            "TIMEOUT" -> R.string.carplay_error_timeout
            "WIFI_RESET", "CAR_HOTSPOT_OFF", "CAR_HOTSPOT_DETAILS_DIFFER", "HOTSPOT_INTERFACE_NOT_FOUND",
            "CAR_HOTSPOT_START_TIMED_OUT", "CAR_HOTSPOT_START_FAILED", "WIFI_DIRECT_CHANNEL_UNAVAILABLE" -> R.string.carplay_error_wifi
            "CHOSEN_IPHONE_NOT_PAIRED", "NO_PAIRED_IPHONE", "IPHONE_NOT_CHOSEN" -> R.string.carplay_stage_pair_phone
            "BLUETOOTH_OR_WIFI" -> R.string.carplay_error_wireless
            else -> R.string.carplay_error_connection
        }
        else -> R.string.carplay_waiting
    }
}

internal object CarPlayOptions {
    private const val PREFS = "carplay_options"
    fun ultra(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("ultra", false)
    fun trace(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("trace", false)
    fun set(context: Context, key: String, enabled: Boolean) {
        require(key == "ultra" || key == "trace")
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(key, enabled).apply()
    }
}
