package com.lenid.zoh.input

import com.lenid.zoh.utils.Settings
import kotlin.math.roundToInt

data class TouchCoordinate(
    val x: Int,
    val y: Int
)

object TouchCoordinateMapper {
    fun map(
        rawX: Float,
        rawY: Float,
        inputSurfaceWidth: Float,
        inputSurfaceHeight: Float,
        negotiatedWidth: Int,
        negotiatedHeight: Int,
        marginWidth: Float,
        marginHeight: Float,
        fitMode: Settings.VideoFitMode,
        hudMirroring: Boolean
    ): TouchCoordinate {
        val surfaceW = inputSurfaceWidth.coerceAtLeast(1f)
        val surfaceH = inputSurfaceHeight.coerceAtLeast(1f)
        val px = if (hudMirroring) surfaceW - rawX else rawX

        val uiW = negotiatedWidth - marginWidth
        val uiH = negotiatedHeight - marginHeight
        // Android Auto draws at the buffer's top-left and leaves the announced margin at the
        // bottom, measured from the coordinate it logged receiving. Centring the canvas here put
        // every tap half the margin low, one control down. The renderer's centre pivot and the
        // symmetric insets both suggest otherwise; neither describes what the phone does.

        val videoX: Float
        val videoY: Float

        if (fitMode == Settings.VideoFitMode.FILL) {
            videoX = (px / surfaceW) * uiW
            videoY = (rawY / surfaceH) * uiH
        } else {
            val uiRatio = uiW / uiH
            val viewRatio = surfaceW / surfaceH

            var displayedUiW = surfaceW
            var displayedUiH = surfaceH

            // CONTAIN picks the smaller fit (letterboxed, <= surface); COVER picks the larger
            // fit (cropped, >= surface) - same shape of math, opposite branch selection.
            val screenIsRelativelyWider = viewRatio > uiRatio
            val matchWidthToHeight = if (fitMode == Settings.VideoFitMode.COVER) !screenIsRelativelyWider else screenIsRelativelyWider

            if (matchWidthToHeight) {
                displayedUiW = surfaceH * uiRatio
            } else {
                displayedUiH = surfaceW / uiRatio
            }

            val uiLeft = (surfaceW - displayedUiW) / 2f
            val uiTop = (surfaceH - displayedUiH) / 2f

            val localX = px - uiLeft
            val localY = rawY - uiTop

            videoX = (localX / displayedUiW) * uiW
            videoY = (localY / displayedUiH) * uiH
        }

        return TouchCoordinate(
            x = videoX.roundToInt().coerceIn(0, negotiatedWidth),
            y = videoY.roundToInt().coerceIn(0, negotiatedHeight)
        )
    }
}
