package com.lenid.zoh.utils

import kotlin.math.roundToInt

/**
 * Which of the two encodings of a non-16:9 panel goes on the wire: a margin (rows or columns the
 * panel cannot show) or the pixel aspect ratio (the panel's real pixel shape). They are
 * alternatives, never layers: wherever a margin is announced the derived ratio cancels to square.
 */
object MarginStrategyPolicy {

    enum class Strategy { PAR, MARGIN }

    /**
     * FILL on a panel wider than its buffer rides on the pixel ratio, so the stored resolution no
     * longer decides whether a third of the frame hides behind a margin the touch mapper never saw.
     * Taller-than-buffer panels and shapes past the clamp keep the margin, which is what is measured.
     */
    fun select(
        mode: Settings.VideoFitMode,
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int
    ): Strategy {
        if (mode != Settings.VideoFitMode.FILL) return Strategy.MARGIN
        if (panelW <= 0 || panelH <= 0 || videoW <= 0 || videoH <= 0) return Strategy.MARGIN
        val derived = ((panelW.toFloat() * videoH) / (panelH.toFloat() * videoW) * 10000f).roundToInt()
        if (derived <= ProjectionGeometryPolicy.SQUARE_PIXELS_E4) return Strategy.MARGIN
        if (derived > ProjectionGeometryPolicy.MAX_PIXEL_ASPECT_E4) return Strategy.MARGIN
        return Strategy.PAR
    }
}
