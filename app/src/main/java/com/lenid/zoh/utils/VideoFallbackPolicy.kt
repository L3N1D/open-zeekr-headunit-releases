package com.lenid.zoh.utils

import com.lenid.zoh.aap.protocol.proto.Control
import kotlin.math.roundToInt

private typealias R = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

/**
 * The second video configuration we offer so a phone that refuses the first can pick a size it
 * accepts. Margins are scaled, not recomputed: the panel scale is pinned at 1 up to 1080p, so a
 * fresh 720p calculation gives other margins and a stretched picture.
 */
object VideoFallbackPolicy {
    /** The position of the fallback in the configurations we announce. */
    const val FALLBACK_INDEX = 1

    data class Margins(val width: Int, val height: Int)

    /** Null for sizes the phone never refuses. */
    fun fallbackFor(primary: R): R? = when (primary) {
        R._1920x1080, R._2560x1440, R._3840x2160 -> R._1280x720
        R._1080x1920, R._1440x2560, R._2160x3840 -> R._720x1280
        else -> null
    }

    fun scaledMargins(primaryW: Int, primaryH: Int, marginW: Int, marginH: Int, fallback: R): Margins {
        val size = sizeOf(fallback)
        if (primaryW <= 0 || primaryH <= 0) return Margins(0, 0)
        return Margins(
            (marginW.toDouble() * size.first / primaryW).roundToInt(),
            (marginH.toDouble() * size.second / primaryH).roundToInt()
        )
    }

    /** Width and height of a resolution type; 800x480 when the name does not parse. */
    fun sizeOf(type: R): Pair<Int, Int> = try {
        val parts = type.toString().replace("_", "").split("x")
        Pair(parts[0].toInt(), parts[1].toInt())
    } catch (e: Exception) {
        Pair(800, 480)
    }

    fun configurationIndices(fallbackOffered: Boolean): List<Int> =
        if (fallbackOffered) listOf(0, FALLBACK_INDEX) else listOf(0)

    /** Whether the phone's Start names the fallback we offered. */
    fun adopts(configurationIndex: Int, offered: Boolean): Boolean =
        offered && configurationIndex == FALLBACK_INDEX
}
