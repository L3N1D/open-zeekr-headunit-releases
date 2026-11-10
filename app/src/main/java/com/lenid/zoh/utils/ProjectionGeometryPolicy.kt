package com.lenid.zoh.utils

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The panel geometry decisions: how far the negotiated video is scaled, what margin that leaves,
 * and what pixel shape the phone should author for. Pure so the ultra-wide cases can be asserted
 * without a device; HeadUnitScreenConfig keeps the Context and DisplayMetrics reading.
 */
object ProjectionGeometryPolicy {

    /** isPortraitScaled is null on a small screen, where recalculate() leaves the previous value standing. */
    data class Fit(
        val scaleFactor: Float,
        val isSmallScreen: Boolean,
        val isPortraitScaled: Boolean?
    )

    fun divideOrOne(numerator: Float, denominator: Float): Float {
        return if (denominator == 0.0f) 1.0f else numerator / denominator
    }

    fun isSmallScreen(panelW: Int, panelH: Int): Boolean {
        return if (panelH > panelW) {
            panelW <= 1080 && panelH <= 1920
        } else {
            panelW <= 1920 && panelH <= 1080
        }
    }

    fun fit(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Fit {
        val small = isSmallScreen(panelW, panelH)
        if (small || videoW <= 0 || videoH <= 0) return Fit(1.0f, small, null)
        val sW = panelW.toFloat()
        val sH = panelH.toFloat()
        val aspect = videoW.toFloat() / videoH.toFloat()
        return if (sW / sH < aspect) {
            Fit(sH / videoH.toFloat(), small, true)
        } else {
            Fit(sW / videoW.toFloat(), small, false)
        }
    }

    fun adjustedWidth(videoW: Int, scaleFactor: Float): Int = (videoW * scaleFactor).roundToInt()

    fun adjustedHeight(videoH: Int, scaleFactor: Float): Int = (videoH * scaleFactor).roundToInt()

    fun widthMargin(videoW: Int, panelW: Int, scaleFactor: Float): Int {
        if (scaleFactor == 0.0f) return 0
        return ((adjustedWidth(videoW, scaleFactor) - panelW) / scaleFactor).roundToInt().coerceAtLeast(0)
    }

    fun heightMargin(videoH: Int, panelH: Int, scaleFactor: Float): Int {
        if (scaleFactor == 0.0f) return 0
        return ((adjustedHeight(videoH, scaleFactor) - panelH) / scaleFactor).roundToInt().coerceAtLeast(0)
    }

    /** The largest uniform scale that fits the canvas entirely inside the panel: the letterbox. */
    fun containScaleFactor(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Float {
        return minOf(
            divideOrOne(panelW.toFloat(), videoW.toFloat()),
            divideOrOne(panelH.toFloat(), videoH.toFloat())
        )
    }

    /**
     * The smallest uniform scale that still covers the panel, cropping the overshoot. Floored at
     * 1.0f so a video that already has the pixels is cropped at native size rather than downscaled.
     */
    fun coverScaleFactor(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Float {
        return maxOf(
            1.0f,
            maxOf(
                divideOrOne(panelW.toFloat(), videoW.toFloat()),
                divideOrOne(panelH.toFloat(), videoH.toFloat())
            )
        )
    }

    // These two size the view on the legacy forcedScale SurfaceView path, which lays the whole
    // buffer out rather than scaling it, so they measure the buffer and not the margin-reduced canvas.
    fun coverWidth(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Int =
        (videoW * coverScaleFactor(panelW, panelH, videoW, videoH)).roundToInt()

    fun coverHeight(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Int =
        (videoH * coverScaleFactor(panelW, panelH, videoW, videoH)).roundToInt()

    /**
     * The visible canvas: the negotiated buffer minus the margin the phone was told to keep clear.
     * Every scale below is expressed against it, because it is what the panel actually shows.
     */
    fun uiWidth(videoW: Int, marginW: Int): Int = videoW - marginW

    fun uiHeight(videoH: Int, marginH: Int): Int = videoH - marginH

    private fun fitFactor(mode: Settings.VideoFitMode, panelW: Int, panelH: Int, uiW: Int, uiH: Int): Float =
        when (mode) {
            Settings.VideoFitMode.COVER -> coverScaleFactor(panelW, panelH, uiW, uiH)
            else -> containScaleFactor(panelW, panelH, uiW, uiH)
        }

    /**
     * FILL asks for the canvas to reach the panel edges, which means scaling the buffer by however
     * much of it the margin hides. Returning 1.0f unconditionally stretched a 720p picture 1.5x on
     * a 1920x720 panel; returning panel aspect over video aspect showed margin rows we had promised
     * the phone were invisible. One expression covers both, and reproduces what the removed
     * videoW > panelW and isPortraitScaled arms computed.
     */
    fun scaleX(
        mode: Settings.VideoFitMode,
        forcedScale: Boolean,
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int,
        marginW: Int,
        marginH: Int
    ): Float {
        if (forcedScale) return 1.0f
        val uiW = uiWidth(videoW, marginW)
        val uiH = uiHeight(videoH, marginH)
        if (mode == Settings.VideoFitMode.FILL) return divideOrOne(videoW.toFloat(), uiW.toFloat())
        return fitFactor(mode, panelW, panelH, uiW, uiH) * divideOrOne(videoW.toFloat(), panelW.toFloat())
    }

    fun scaleY(
        mode: Settings.VideoFitMode,
        forcedScale: Boolean,
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int,
        marginW: Int,
        marginH: Int
    ): Float {
        if (forcedScale) return 1.0f
        val uiW = uiWidth(videoW, marginW)
        val uiH = uiHeight(videoH, marginH)
        if (mode == Settings.VideoFitMode.FILL) return divideOrOne(videoH.toFloat(), uiH.toFloat())
        return fitFactor(mode, panelW, panelH, uiW, uiH) * divideOrOne(videoH.toFloat(), panelH.toFloat())
    }

    /** 10000 means square pixels, and doubles as "the user has not set one". */
    const val SQUARE_PIXELS_E4 = 10000

    /** Half as wide as tall, and twice as wide as tall. No real panel is outside this. */
    const val MIN_PIXEL_ASPECT_E4 = 5000
    const val MAX_PIXEL_ASPECT_E4 = 20000

    /**
     * The pixel shape to advertise so the phone lays its UI out for the real panel while still
     * encoding a 16:9 buffer. Measured against the margin-reduced canvas, because the margins
     * already describe a correctly shaped canvas inside a bigger buffer. The phone pre-compensates
     * by 10000/this and the panel's own stretch cancels it, so sending the reciprocal doubles it.
     */
    fun pixelAspectRatioE4(
        mode: Settings.VideoFitMode,
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int,
        marginW: Int,
        marginH: Int
    ): Int {
        // Only FILL stretches the canvas to the panel. CONTAIN and COVER keep the aspect themselves,
        // so asking the phone to pre-compensate would squeeze its UI by the correction.
        if (mode != Settings.VideoFitMode.FILL) return SQUARE_PIXELS_E4
        val canvasW = uiWidth(videoW, marginW)
        val canvasH = uiHeight(videoH, marginH)
        if (panelW <= 0 || panelH <= 0 || canvasW <= 0 || canvasH <= 0) return SQUARE_PIXELS_E4
        val derived = ((panelW.toFloat() * canvasH) / (panelH.toFloat() * canvasW) * 10000f).roundToInt()
        // A panel reading this far from square is a bad measurement, not a panel; say square rather
        // than put it on the wire.
        if (derived < MIN_PIXEL_ASPECT_E4 || derived > MAX_PIXEL_ASPECT_E4) return SQUARE_PIXELS_E4
        // Within a few percent of square, say square: the phone gains nothing from the correction.
        // This is the dial to widen if a panel class turns out not to want the correction.
        val deviation = abs(derived - SQUARE_PIXELS_E4) / SQUARE_PIXELS_E4.toFloat()
        return if (deviation > 0.03f) derived else SQUARE_PIXELS_E4
    }

    /**
     * Margins that keep the pixel shape announced at connection time when the panel is resized.
     * The phone cannot be sent a new pixel aspect ratio mid-session, so the visible canvas changes
     * instead. Null means the ordinary panel margins already preserve that shape.
     */
    fun sessionCompensatedMargins(
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int,
        announcedParE4: Int,
    ): Pair<Int, Int>? {
        if (panelW <= 0 || panelH <= 0 || videoW <= 0 || videoH <= 0 || announcedParE4 <= 0) return null
        val strategy = MarginStrategyPolicy.select(
            Settings.VideoFitMode.FILL, panelW, panelH, videoW, videoH
        )
        val wantedPar = if (strategy == MarginStrategyPolicy.Strategy.PAR) {
            pixelAspectRatioE4(
                Settings.VideoFitMode.FILL, panelW, panelH, videoW, videoH, 0, 0
            )
        } else {
            SQUARE_PIXELS_E4
        }
        if (wantedPar == announcedParE4) return null

        val pixelShape = announcedParE4 / SQUARE_PIXELS_E4.toFloat()
        var canvasW = videoW.toFloat()
        var canvasH = canvasW * panelH * pixelShape / panelW
        if (canvasH > videoH) {
            canvasH = videoH.toFloat()
            canvasW = canvasH * panelW / (panelH * pixelShape)
        }
        return (videoW - canvasW).roundToInt().coerceAtLeast(0) to
            (videoH - canvasH).roundToInt().coerceAtLeast(0)
    }
}
