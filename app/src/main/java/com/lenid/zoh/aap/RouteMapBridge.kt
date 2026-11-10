package com.lenid.zoh.aap

import android.content.Context
import android.content.Intent
import com.lenid.zoh.aap.protocol.proto.NavigationStatus
import com.lenid.zoh.utils.AppLog

/**
 * Hands Android Auto guidance to the companion "OH Route Map" app (package [TARGET_PACKAGE]),
 * which geocodes the destination, routes to it itself and draws the line on a map. Android Auto
 * never sends route geometry, so the destination address is what the map app works from.
 *
 * The broadcast is explicit (setPackage), so it needs no permission and reaches the app's
 * manifest receiver even while it is not running. Only the route map app receives it.
 */
internal class RouteMapBridge(private val context: Context) {

    private var guiding = false

    /**
     * The last guidance, sent again every [REPEAT_MS] while it holds: the phone may say nothing
     * for minutes (standing still), and a route map app started meanwhile (updated, or killed by
     * the system) would otherwise not know there is a route.
     */
    private var last: Intent? = null
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val repeat = object : Runnable {
        override fun run() {
            val intent = last ?: return
            try { context.sendBroadcast(intent) } catch (_: Exception) {}
            main.postDelayed(this, REPEAT_MS)
        }
    }

    fun update(
        guidance: AutoNaviGuidanceBridge.Guidance?,
        destinationAddress: String?,
        state: NavigationStatus.NavigationState? = null
    ) {
        try {
            if (guidance == null) {
                if (last != null) main.removeCallbacks(repeat)
                last = null
                if (guiding) context.sendBroadcast(baseIntent().putExtra(EXTRA_ACTIVE, false))
                guiding = false
                return
            }
            guiding = true
            val intent = baseIntent().apply {
                putExtra(EXTRA_ACTIVE, true)
                putExtra(EXTRA_DESTINATION, destinationAddress.orEmpty())
                putExtra(EXTRA_MANEUVER, guidance.maneuverType?.name.orEmpty())
                putExtra(EXTRA_LEGACY_EVENT, guidance.legacyNextEvent)
                putExtra(EXTRA_LEGACY_SIDE, guidance.legacySide ?: -1)
                putExtra(EXTRA_ROUNDABOUT_EXIT, guidance.roundaboutExit ?: -1)
                putExtra(EXTRA_STEP_DISTANCE, guidance.segmentDistanceMeters ?: -1)
                putExtra(EXTRA_STEP_TIME, guidance.segmentTimeSeconds ?: -1)
                putExtra(EXTRA_NEXT_ROAD, guidance.nextRoad)
                putExtra(EXTRA_CURRENT_ROAD, guidance.currentRoad)
                putExtra(EXTRA_TOTAL_DISTANCE, guidance.remainingDistanceMeters ?: -1)
                putExtra(EXTRA_TOTAL_TIME, guidance.remainingTimeSeconds ?: -1L)
                putExtra(EXTRA_ETA, guidance.estimatedArrival.orEmpty())
                putExtra(EXTRA_LANES, lanes(state?.stepsList?.firstOrNull()))
                putExtra(EXTRA_THEN_MANEUVER, state?.stepsList?.getOrNull(1)
                    ?.takeIf { it.hasManeuver() && it.maneuver.hasType() }?.maneuver?.type?.name.orEmpty())
            }
            context.sendBroadcast(intent)
            last = intent
            main.removeCallbacks(repeat)
            main.postDelayed(repeat, REPEAT_MS)
        } catch (e: Exception) {
            AppLog.w("RouteMapBridge: broadcast failed: ${e.message}")
        }
    }

    /**
     * Lanes of the next step, left to right, as "SHAPE,SHAPE*;SHAPE": lanes split by ';', the
     * directions of one lane by ',', '*' marks a direction to take. Empty when none are sent.
     */
    private fun lanes(step: NavigationStatus.NavigationStep?): String =
        step?.lanesList.orEmpty().joinToString(";") { lane ->
            lane.laneDirectionsList.joinToString(",") { d ->
                d.shape.name + if (d.hasIsHighlighted() && d.isHighlighted) "*" else ""
            }
        }

    private fun baseIntent() = Intent(ACTION)
        .setPackage(TARGET_PACKAGE)
        // Also wake the map app after a force stop, which would otherwise drop it until opened by hand.
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    companion object {
        const val TARGET_PACKAGE = "com.lenid.ohroutemap"
        const val ACTION = "com.lenid.ohroutemap.ROUTE_GUIDANCE"
        private const val REPEAT_MS = 15_000L

        /** false once guidance has ended; the other extras are then absent. */
        const val EXTRA_ACTIVE = "active"
        /** `NavigationState.destinations[0].address`, or empty when the nav app sends none. */
        const val EXTRA_DESTINATION = "destination"
        /** `NavigationManeuver.NavigationType` name (e.g. TURN_NORMAL_LEFT), or empty. */
        const val EXTRA_MANEUVER = "maneuver"
        const val EXTRA_LEGACY_EVENT = "legacy_next_event"
        const val EXTRA_LEGACY_SIDE = "legacy_side"
        const val EXTRA_ROUNDABOUT_EXIT = "roundabout_exit"
        const val EXTRA_STEP_DISTANCE = "step_distance_m"
        const val EXTRA_STEP_TIME = "step_time_s"
        const val EXTRA_NEXT_ROAD = "next_road"
        const val EXTRA_CURRENT_ROAD = "current_road"
        const val EXTRA_TOTAL_DISTANCE = "total_distance_m"
        const val EXTRA_TOTAL_TIME = "total_time_s"
        const val EXTRA_ETA = "eta"
        /** See [lanes]. */
        const val EXTRA_LANES = "lanes"
        /** Maneuver after the next one (`NavigationType` name), or empty. */
        const val EXTRA_THEN_MANEUVER = "then_maneuver"
    }
}

/**
 * Tells the "OH Route Map" app about a track change (title, artist and a small album cover), so
 * it can show the track on the HUD for a few seconds. Only a new song/artist pair, or a new cover
 * for the same pair, is sent: the phone repeats its metadata, and a pause or seek must not bring
 * the card back. The cover often follows the title a moment later in a second message, which
 * the app takes as "same track, here is its cover".
 *
 * Explicit and not waking a stopped app: the card only makes sense while that app draws the HUD.
 */
internal class RouteMapTrackBridge(private val context: Context) {

    private var lastKey: String? = null
    private var lastArt: Int? = null

    /** [artKey]: identifies the cover bytes (null without one), so a repeat of the same cover is not sent. */
    fun update(song: String, artist: String, album: String, art: android.graphics.Bitmap?, artKey: Int?) {
        val key = "$song\u0000$artist"
        if (song.isBlank()) return
        if (key == lastKey && (artKey == null || artKey == lastArt)) return
        lastKey = key
        if (artKey != null) lastArt = artKey
        try {
            context.sendBroadcast(Intent(ACTION).setPackage(RouteMapBridge.TARGET_PACKAGE).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(EXTRA_TITLE, song)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_ALBUM, album)
                art?.let { putExtra(EXTRA_ART, jpeg(it)) }
            })
        } catch (e: Exception) {
            AppLog.w("RouteMapTrackBridge: broadcast failed: ${e.message}")
        }
    }

    private var lastPlaying: Boolean? = null

    /**
     * Playing/paused changed (the phone repeats its status every second; only a change is sent).
     * The first state of a session is only remembered, so connecting does not show "paused".
     */
    fun playState(playing: Boolean, song: String, artist: String, art: android.graphics.Bitmap?) {
        val before = lastPlaying
        lastPlaying = playing
        if (before == null || before == playing || song.isBlank()) return
        try {
            context.sendBroadcast(Intent(ACTION).setPackage(RouteMapBridge.TARGET_PACKAGE).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(EXTRA_EVENT, EVENT_STATE)
                putExtra(EXTRA_PLAYING, playing)
                putExtra(EXTRA_TITLE, song)
                putExtra(EXTRA_ARTIST, artist)
                art?.let { putExtra(EXTRA_ART, jpeg(it)) }
            })
        } catch (e: Exception) {
            AppLog.w("RouteMapTrackBridge: state broadcast failed: ${e.message}")
        }
    }

    /** The session ended: the next session's first track is news again. */
    fun reset() { lastKey = null; lastArt = null; lastPlaying = null }

    /** Small enough for a broadcast (well under the binder limit) and for a HUD card. */
    private fun jpeg(art: android.graphics.Bitmap): ByteArray {
        val side = maxOf(art.width, art.height)
        val scaled = if (side > ART_SIZE) {
            val k = ART_SIZE.toFloat() / side
            android.graphics.Bitmap.createScaledBitmap(art, (art.width * k).toInt().coerceAtLeast(1),
                (art.height * k).toInt().coerceAtLeast(1), true)
        } else art
        return java.io.ByteArrayOutputStream().use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    companion object {
        const val ACTION = "com.lenid.ohroutemap.TRACK"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        /** JPEG bytes, at most [ART_SIZE] px on the longer side; absent without a cover. */
        const val EXTRA_ART = "art"
        /** Absent for a new track; [EVENT_STATE] for a play/pause change, with [EXTRA_PLAYING]. */
        const val EXTRA_EVENT = "event"
        const val EVENT_STATE = "state"
        const val EXTRA_PLAYING = "playing"
        private const val ART_SIZE = 320
    }
}
