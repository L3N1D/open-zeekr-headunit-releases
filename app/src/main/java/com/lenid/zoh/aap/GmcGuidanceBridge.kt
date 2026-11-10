package com.lenid.zoh.aap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.lenid.zoh.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.lenid.zoh.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent
import com.lenid.zoh.decoder.audio.CarAudioPlatform
import com.lenid.zoh.utils.AppLog
import java.util.Locale

/**
 * Feeds Android Auto turn-by-turn guidance to the GMC mod (com.logstas.*) used on Zeekr/ECARX
 * cars. GMC drives the instrument cluster and HUD from `com.frida.injector.SEND`, which its hook
 * inside Yandex Navigator normally emits; mirroring that broadcast with "Yandex Navigator"
 * selected in GMC puts the phone's route there instead.
 *
 * The extras match what the hook sends: every value is a display string, and `icon` is the name
 * of a Yandex maneuver drawable (context_ra_*), which GMC maps to its own arrows.
 */
internal class GmcGuidanceBridge(private val context: Context) {

    private var guiding = false
    private var feedCheckedAt = 0L
    private var feedCached = true

    /**
     * The "OH Route Map" app can switch the GMC feed off when it draws the HUD itself. Read from
     * its provider at most every 5 s; without that app, or on any error, GMC is fed as before.
     */
    private fun feedAllowed(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (feedCheckedAt != 0L && now - feedCheckedAt < 5000) return feedCached
        feedCheckedAt = now
        feedCached = try {
            context.contentResolver.query(
                android.net.Uri.parse("content://com.lenid.ohroutemap.settings/gmc"), null, null, null, null
            )?.use { c -> !c.moveToFirst() || c.getInt(0) != 0 } ?: true
        } catch (_: Exception) {
            true
        }
        return feedCached
    }

    private val gmcInstalled: Boolean by lazy {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(GMC_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun update(guidance: AutoNaviGuidanceBridge.Guidance?) {
        if (!CarAudioPlatform.isCarPlatform || !gmcInstalled) return
        try {
            if (!feedAllowed()) {
                // Switched off in the route map app: clear GMC once, then stay quiet.
                if (guiding) sendClear()
                guiding = false
                return
            }
            if (guidance == null) {
                // Guidance ended: clear the arrow the way the hook does on a route reset.
                if (guiding) sendClear()
                guiding = false
                return
            }
            guiding = true
            val (distance, unit) = formatStepDistance(guidance.segmentDistanceMeters)
            send(
                icon = guidance.maneuverType?.let { iconFor(it) }
                    ?: legacyIcon(guidance.legacyNextEvent, guidance.legacySide),
                distance = distance,
                unit = unit,
                street = guidance.nextRoad,
                distanceLeft = formatTotalDistance(guidance.remainingDistanceMeters),
                timeLeft = formatDuration(guidance.remainingTimeSeconds),
                arrivedTime = guidance.estimatedArrival.orEmpty()
            )
        } catch (e: Exception) {
            AppLog.w("GmcGuidanceBridge: broadcast failed: ${e.message}")
        }
    }

    /**
     * End of guidance, as the hook sends it when a route is reset: type "clear" and no fields.
     * GMC keeps the last arrow on screen after a "process" with empty fields.
     */
    private fun sendClear() {
        context.sendBroadcast(Intent(ACTION).apply {
            putExtra("action", "navi")
            putExtra("type", "clear")
            putExtra("package", YANDEX_NAVI_PACKAGE)
        })
    }

    private fun send(
        icon: String, distance: String, unit: String, street: String,
        distanceLeft: String, timeLeft: String, arrivedTime: String
    ) {
        context.sendBroadcast(Intent(ACTION).apply {
            putExtra("action", "navi")
            putExtra("type", "process")
            putExtra("package", YANDEX_NAVI_PACKAGE)
            putExtra("progress", -1)
            putExtra("icon", icon)
            putExtra("distance", distance)
            putExtra("unit", unit)
            putExtra("street", street)
            putExtra("distanceLeft", distanceLeft)
            putExtra("timeLeft", timeLeft)
            putExtra("arrivedTime", arrivedTime)
        })
    }

    /** "100" + "м" below a kilometre, "1,2" + "км" above, as Yandex shows it. */
    private fun formatStepDistance(meters: Int?): Pair<String, String> {
        if (meters == null) return "" to ""
        if (meters < 1000) {
            val rounded = if (meters >= 100) meters / 50 * 50 else meters / 10 * 10
            return rounded.toString() to "м"
        }
        val km = meters / 1000.0
        val text = if (km < 10) String.format(RU, "%.1f", km) else (meters / 1000).toString()
        return text to "км"
    }

    private fun formatTotalDistance(meters: Int?): String {
        if (meters == null) return ""
        if (meters < 1000) return "$meters м"
        val km = meters / 1000.0
        return if (km < 10) String.format(RU, "%.1f км", km) else "${meters / 1000} км"
    }

    /** "13 мин", "1 ч 7 мин". */
    private fun formatDuration(seconds: Long?): String {
        if (seconds == null) return ""
        val minutes = (seconds + 30) / 60
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$rest мин"
            rest == 0L -> "$hours ч"
            else -> "$hours ч $rest мин"
        }
    }

    private fun iconFor(type: NavigationType): String = when (type) {
        NavigationType.TURN_NORMAL_LEFT, NavigationType.ON_RAMP_NORMAL_LEFT -> "context_ra_turn_left"
        NavigationType.TURN_NORMAL_RIGHT, NavigationType.ON_RAMP_NORMAL_RIGHT -> "context_ra_turn_right"
        NavigationType.TURN_SLIGHT_LEFT, NavigationType.KEEP_LEFT, NavigationType.FORK_LEFT,
        NavigationType.ON_RAMP_SLIGHT_LEFT, NavigationType.MERGE_LEFT -> "context_ra_take_left"
        NavigationType.TURN_SLIGHT_RIGHT, NavigationType.KEEP_RIGHT, NavigationType.FORK_RIGHT,
        NavigationType.ON_RAMP_SLIGHT_RIGHT, NavigationType.MERGE_RIGHT -> "context_ra_take_right"
        NavigationType.OFF_RAMP_NORMAL_LEFT, NavigationType.OFF_RAMP_SLIGHT_LEFT -> "context_ra_exit_left"
        NavigationType.OFF_RAMP_NORMAL_RIGHT, NavigationType.OFF_RAMP_SLIGHT_RIGHT -> "context_ra_exit_right"
        NavigationType.TURN_SHARP_LEFT, NavigationType.ON_RAMP_SHARP_LEFT -> "context_ra_hard_turn_left"
        NavigationType.TURN_SHARP_RIGHT, NavigationType.ON_RAMP_SHARP_RIGHT -> "context_ra_hard_turn_right"
        NavigationType.U_TURN_LEFT, NavigationType.ON_RAMP_U_TURN_LEFT -> "context_ra_turn_back_left"
        NavigationType.U_TURN_RIGHT, NavigationType.ON_RAMP_U_TURN_RIGHT -> "context_ra_turn_back_right"
        NavigationType.ROUNDABOUT_ENTER, NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> "context_ra_in_circular_movement"
        NavigationType.ROUNDABOUT_EXIT -> "context_ra_out_circular_movement"
        NavigationType.DESTINATION, NavigationType.DESTINATION_STRAIGHT,
        NavigationType.DESTINATION_LEFT, NavigationType.DESTINATION_RIGHT -> "context_ra_finish"
        NavigationType.FERRY_BOAT, NavigationType.FERRY_TRAIN -> "context_ra_boardferry"
        NavigationType.DEPART, NavigationType.NAME_CHANGE, NavigationType.STRAIGHT,
        NavigationType.MERGE_SIDE_UNSPECIFIED, NavigationType.UNKNOWN -> "context_ra_forward"
    }

    /** Fallback for phones that still send only the deprecated NextTurnDetail. */
    private fun legacyIcon(nextEvent: Int, side: Int?): String {
        val left = side == SIDE_LEFT
        return when (nextEvent) {
            NextEvent.TURN_VALUE -> if (left) "context_ra_turn_left" else "context_ra_turn_right"
            NextEvent.SLIGHT_TURN_VALUE, NextEvent.FORK_VALUE, NextEvent.ON_RAMP_VALUE ->
                if (left) "context_ra_take_left" else "context_ra_take_right"
            NextEvent.OFFRAMP_VALUE -> if (left) "context_ra_exit_left" else "context_ra_exit_right"
            NextEvent.SHARP_TURN_VALUE -> if (left) "context_ra_hard_turn_left" else "context_ra_hard_turn_right"
            NextEvent.U_TURN_VALUE -> if (left) "context_ra_turn_back_left" else "context_ra_turn_back_right"
            NextEvent.ROUNDABOUT_ENTER_VALUE, NextEvent.ROUNDABOUT_ENTER_AND_EXIT_VALUE -> "context_ra_in_circular_movement"
            NextEvent.ROUNDABOUT_EXIT_VALUE -> "context_ra_out_circular_movement"
            NextEvent.DESTINATION_VALUE -> "context_ra_finish"
            NextEvent.FERRY_BOAT_VALUE, NextEvent.FERRY_TRAIN_VALUE -> "context_ra_boardferry"
            else -> "context_ra_forward"
        }
    }

    private companion object {
        const val ACTION = "com.frida.injector.SEND"
        const val GMC_PACKAGE = "com.logstas.myregion"
        const val YANDEX_NAVI_PACKAGE = "ru.yandex.yandexnavi"
        const val SIDE_LEFT = 1
        val RU: Locale = Locale("ru")
    }
}
