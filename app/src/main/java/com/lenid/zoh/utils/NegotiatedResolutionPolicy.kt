package com.lenid.zoh.utils

/**
 * Which resolution the AUTO ladder asks the phone for. Speaks Settings.Resolution so it stays
 * testable without the generated proto; HeadUnitScreenConfig maps the answer to the wire type and
 * applies the panel and link ceilings on top.
 */
object NegotiatedResolutionPolicy {

    /**
     * The AUTO choice for a panel. Every proto resolution is 16:9 bar 480p, so the rows are the
     * only dimension a panel can match exactly. In FILL a wider panel is a pixel shape question,
     * answered by ProjectionGeometryPolicy.pixelAspectRatioE4, so the short side alone decides.
     * CONTAIN and COVER announce square pixels, so there the buffer's columns are the only width.
     */
    fun autoLadder(
        panelW: Int,
        panelH: Int,
        fitMode: Settings.VideoFitMode,
        hevcSupported: Boolean,
        canHevcHighRes: Boolean,
        sdkInt: Int
    ): Settings.Resolution {
        val longSide = maxOf(panelW, panelH)
        val shortSide = minOf(panelW, panelH)
        // Without the pixel aspect ratio, a 720-row wide panel on 720p is a 1280-wide picture
        // between pillars, where 1080p with a height margin fills the width at 1:1.
        val wide = if (fitMode == Settings.VideoFitMode.FILL) 0 else longSide
        return when {
            longSide <= 800 && shortSide <= 480 -> Settings.Resolution._800x480
            (shortSide >= 2160 || wide >= 3840) && hevcSupported && sdkInt >= 24 -> Settings.Resolution._3840x2160
            (shortSide >= 1440 || wide >= 2560) && canHevcHighRes && sdkInt >= 24 -> Settings.Resolution._2560x1440
            shortSide > 720 || wide > 1280 -> Settings.Resolution._1920x1080
            else -> Settings.Resolution._1280x720
        }
    }

    /**
     * What a session should renegotiate to, or null to keep what is already negotiated.
     *
     * A locked session keeps its resolution. It used to fall through to the manual branch, where
     * AUTO carries no codec and the fallback landed on 480p, so a locked AUTO session downgraded
     * itself mid-drive.
     */
    fun select(
        isLocked: Boolean,
        selected: Settings.Resolution?,
        panelW: Int,
        panelH: Int,
        fitMode: Settings.VideoFitMode,
        hevcSupported: Boolean,
        canHevcHighRes: Boolean,
        sdkInt: Int
    ): Settings.Resolution? {
        if (isLocked) return null
        if (selected == null || selected == Settings.Resolution.AUTO) {
            return autoLadder(panelW, panelH, fitMode, hevcSupported, canHevcHighRes, sdkInt)
        }
        return selected
    }
}
