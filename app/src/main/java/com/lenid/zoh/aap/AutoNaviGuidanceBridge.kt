package com.lenid.zoh.aap

import android.content.Context
import android.content.Intent
import com.lenid.zoh.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.lenid.zoh.aap.protocol.proto.NavigationStatus.NextTurnDetail.NextEvent
import com.lenid.zoh.decoder.audio.CarAudioPlatform
import com.lenid.zoh.utils.AppLog

/**
 * Re-announces Android Auto turn-by-turn guidance in the AutoNavi (Amap Auto) public broadcast
 * format. Chinese car firmwares, Geely/ECARX mods among them, drive the instrument cluster and
 * HUD turn arrows from `AUTONAVI_STANDARD_BROADCAST_SEND`, so this lets the phone's route show
 * there. Android Auto sends maneuvers, not route geometry: the car gets the arrow, distances,
 * road names and ETA, not a line on its own map.
 *
 * Only enabled on car platforms: the broadcast is implicit, so any app could read the route.
 */
internal class AutoNaviGuidanceBridge(private val context: Context) {

    data class Guidance(
        val maneuverType: NavigationType?,
        val legacyNextEvent: Int,
        val legacySide: Int?,
        val roundaboutExit: Int?,
        val segmentDistanceMeters: Int?,
        val segmentTimeSeconds: Int?,
        val nextRoad: String,
        val currentRoad: String,
        val remainingDistanceMeters: Int?,
        val remainingTimeSeconds: Long?,
        val estimatedArrival: String?
    )

    private var guiding = false

    fun update(guidance: Guidance?) {
        if (!CarAudioPlatform.isCarPlatform) return
        try {
            if (guidance == null) {
                if (guiding) sendState(STATE_NAVIGATION_END)
                guiding = false
                return
            }
            if (!guiding) {
                sendState(STATE_NAVIGATION_START)
                guiding = true
            }
            sendGuidance(guidance)
        } catch (e: Exception) {
            AppLog.w("AutoNaviGuidanceBridge: broadcast failed: ${e.message}")
        }
    }

    private fun sendState(state: Int) {
        context.sendBroadcast(Intent(ACTION).apply {
            putExtra(KEY_TYPE, TYPE_STATE)
            putExtra("EXTRA_STATE", state)
        })
    }

    private fun sendGuidance(g: Guidance) {
        val icon = g.maneuverType?.let { iconFor(it) } ?: legacyIcon(g.legacyNextEvent, g.legacySide)
        context.sendBroadcast(Intent(ACTION).apply {
            putExtra(KEY_TYPE, TYPE_GUIDANCE)
            putExtra("TYPE", 0) // 0 = real GPS guidance, 1 = simulation
            putExtra("ICON", icon)
            putExtra("NEW_ICON", icon)
            putExtra("NEXT_ROAD_NAME", g.nextRoad)
            putExtra("CUR_ROAD_NAME", g.currentRoad)
            putExtra("SEG_REMAIN_DIS", g.segmentDistanceMeters ?: 0)
            putExtra("SEG_REMAIN_TIME", g.segmentTimeSeconds ?: 0)
            putExtra("ROUTE_REMAIN_DIS", g.remainingDistanceMeters ?: 0)
            putExtra("ROUTE_REMAIN_TIME", (g.remainingTimeSeconds ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            g.estimatedArrival?.let { putExtra("ETA_TEXT", it) }
            g.roundaboutExit?.takeIf { it > 0 }?.let {
                putExtra("ROUNDABOUT_NUM", it)
                putExtra("ROUND_ABOUT_NUM", it)
            }
        })
    }

    private fun iconFor(type: NavigationType): Int = when (type) {
        NavigationType.TURN_NORMAL_LEFT, NavigationType.ON_RAMP_NORMAL_LEFT,
        NavigationType.OFF_RAMP_NORMAL_LEFT, NavigationType.DESTINATION_LEFT -> ICON_LEFT
        NavigationType.TURN_NORMAL_RIGHT, NavigationType.ON_RAMP_NORMAL_RIGHT,
        NavigationType.OFF_RAMP_NORMAL_RIGHT, NavigationType.DESTINATION_RIGHT -> ICON_RIGHT
        NavigationType.TURN_SLIGHT_LEFT, NavigationType.KEEP_LEFT, NavigationType.FORK_LEFT,
        NavigationType.ON_RAMP_SLIGHT_LEFT, NavigationType.OFF_RAMP_SLIGHT_LEFT,
        NavigationType.MERGE_LEFT -> ICON_SLIGHT_LEFT
        NavigationType.TURN_SLIGHT_RIGHT, NavigationType.KEEP_RIGHT, NavigationType.FORK_RIGHT,
        NavigationType.ON_RAMP_SLIGHT_RIGHT, NavigationType.OFF_RAMP_SLIGHT_RIGHT,
        NavigationType.MERGE_RIGHT -> ICON_SLIGHT_RIGHT
        NavigationType.TURN_SHARP_LEFT, NavigationType.ON_RAMP_SHARP_LEFT -> ICON_SHARP_LEFT
        NavigationType.TURN_SHARP_RIGHT, NavigationType.ON_RAMP_SHARP_RIGHT -> ICON_SHARP_RIGHT
        NavigationType.U_TURN_LEFT, NavigationType.ON_RAMP_U_TURN_LEFT -> ICON_U_TURN_LEFT
        NavigationType.U_TURN_RIGHT, NavigationType.ON_RAMP_U_TURN_RIGHT -> ICON_U_TURN_RIGHT
        NavigationType.ROUNDABOUT_ENTER, NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE -> ICON_ROUNDABOUT_ENTER
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
        NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE -> ICON_ROUNDABOUT_ENTER_CCW
        NavigationType.ROUNDABOUT_EXIT -> ICON_ROUNDABOUT_EXIT
        NavigationType.DESTINATION, NavigationType.DESTINATION_STRAIGHT -> ICON_DESTINATION
        NavigationType.DEPART, NavigationType.NAME_CHANGE, NavigationType.STRAIGHT,
        NavigationType.MERGE_SIDE_UNSPECIFIED, NavigationType.FERRY_BOAT,
        NavigationType.FERRY_TRAIN, NavigationType.UNKNOWN -> ICON_STRAIGHT
    }

    /** Fallback for phones that still send only the deprecated NextTurnDetail. */
    private fun legacyIcon(nextEvent: Int, side: Int?): Int {
        val left = side == SIDE_LEFT
        return when (nextEvent) {
            NextEvent.TURN_VALUE -> if (left) ICON_LEFT else ICON_RIGHT
            NextEvent.SLIGHT_TURN_VALUE, NextEvent.FORK_VALUE, NextEvent.ON_RAMP_VALUE,
            NextEvent.OFFRAMP_VALUE -> if (left) ICON_SLIGHT_LEFT else ICON_SLIGHT_RIGHT
            NextEvent.SHARP_TURN_VALUE -> if (left) ICON_SHARP_LEFT else ICON_SHARP_RIGHT
            NextEvent.U_TURN_VALUE -> if (left) ICON_U_TURN_LEFT else ICON_U_TURN_RIGHT
            NextEvent.ROUNDABOUT_ENTER_VALUE, NextEvent.ROUNDABOUT_ENTER_AND_EXIT_VALUE -> ICON_ROUNDABOUT_ENTER
            NextEvent.ROUNDABOUT_EXIT_VALUE -> ICON_ROUNDABOUT_EXIT
            NextEvent.DESTINATION_VALUE -> ICON_DESTINATION
            else -> ICON_STRAIGHT
        }
    }

    private companion object {
        const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        const val KEY_TYPE = "KEY_TYPE"
        const val TYPE_GUIDANCE = 10001
        const val TYPE_STATE = 10019
        const val STATE_NAVIGATION_START = 8
        const val STATE_NAVIGATION_END = 9

        const val SIDE_LEFT = 1

        // AutoNavi guidance icon ids.
        const val ICON_LEFT = 2
        const val ICON_RIGHT = 3
        const val ICON_SLIGHT_LEFT = 4
        const val ICON_SLIGHT_RIGHT = 5
        const val ICON_SHARP_LEFT = 6
        const val ICON_SHARP_RIGHT = 7
        const val ICON_U_TURN_LEFT = 8
        const val ICON_STRAIGHT = 9
        const val ICON_ROUNDABOUT_ENTER = 11
        const val ICON_ROUNDABOUT_EXIT = 12
        const val ICON_DESTINATION = 15
        const val ICON_ROUNDABOUT_ENTER_CCW = 17
        const val ICON_U_TURN_RIGHT = 19
    }
}
