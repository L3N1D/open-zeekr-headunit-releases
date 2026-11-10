package com.lenid.zoh.utils

import kotlin.math.roundToInt

/**
 * The two Zeekr launcher card geometries verified in the car on 2026-11-06.
 *
 * The reference session used a 1920x1080 stream. The compact card showed a 1275x876
 * canvas (645x204 total margins); the wide card showed 1920x936 (0x144). Keeping the
 * same canvas proportions at 720p reproduces the verified scale without raising the
 * wireless profile or changing Android's display density.
 */
object ZeekrCardProjectionPolicy {
    private const val REFERENCE_VIDEO_W = 1920
    private const val REFERENCE_VIDEO_H = 1080
    private const val COMPACT_W = 1275
    private const val WIDE_W = 1796
    private const val CARD_H = 876

    fun isReferencePanel(panelW: Int, panelH: Int): Boolean =
        panelH == CARD_H && (panelW == COMPACT_W || panelW == WIDE_W)

    /** Visible canvas inside the reference 1080p buffer. */
    fun referenceCanvas(panelW: Int, panelH: Int): Pair<Int, Int>? {
        if (!isReferencePanel(panelW, panelH)) return null
        if (panelW == COMPACT_W) return COMPACT_W to CARD_H
        val canvasH = (REFERENCE_VIDEO_W.toFloat() * panelH / panelW).roundToInt()
        return REFERENCE_VIDEO_W to (REFERENCE_VIDEO_H - (REFERENCE_VIDEO_H - canvasH))
    }

    /**
     * Density is session-wide: connecting with the side widgets open must produce the same
     * legible controls as connecting with them closed. Always use the wide reference for the
     * automatic density; only margins change when the widgets move, without reconnecting AA.
     */
    fun densityCanvas(panelW: Int, panelH: Int): Pair<Int, Int>? =
        if (isReferencePanel(panelW, panelH)) referenceCanvas(WIDE_W, CARD_H) else null

    /** Total (width, height) margins, scaled proportionally for 720p and 1080p. */
    fun margins(
        panelW: Int, panelH: Int, videoW: Int, videoH: Int,
        sessionParE4: Int = ProjectionGeometryPolicy.SQUARE_PIXELS_E4,
    ): Pair<Int, Int>? {
        val reference = referenceCanvas(panelW, panelH) ?: return null
        if (!supportsVideo(videoW, videoH)) return null
        if (sessionParE4 <= 0) return null
        var canvasW = reference.first.toFloat() * videoW / REFERENCE_VIDEO_W
        // The phone retains the pixel shape negotiated at connection, including when the
        // session started fullscreen. Fit that shape into the buffer instead of stretching it.
        var canvasH = canvasW * panelH / panelW *
            (sessionParE4 / ProjectionGeometryPolicy.SQUARE_PIXELS_E4.toFloat())
        if (canvasH > videoH) {
            canvasW *= videoH / canvasH
            canvasH = videoH.toFloat()
        }
        return (videoW - canvasW.roundToInt()).coerceAtLeast(0) to
            (videoH - canvasH.roundToInt()).coerceAtLeast(0)
    }

    /** Preserve the reference dp canvas when the wireless profile selects 720p. */
    fun scaledAutomaticDensity(baseDensity: Int, videoW: Int, videoH: Int): Int? {
        if (baseDensity <= 0 || !supportsVideo(videoW, videoH)) return null
        return (baseDensity.toFloat() * videoW / REFERENCE_VIDEO_W).roundToInt().coerceAtLeast(1)
    }

    private fun supportsVideo(videoW: Int, videoH: Int): Boolean =
        (videoW == 1280 && videoH == 720) || (videoW == REFERENCE_VIDEO_W && videoH == REFERENCE_VIDEO_H)
}
