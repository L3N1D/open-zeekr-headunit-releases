package com.lenid.zoh.utils

/**
 * Which way up a reading is meant to be. Four call sites each grew their own copy of this swap and
 * two omitted the AUTO/SYSTEM fallback, so a portrait window reading was adopted as the landscape
 * canvas and a portrait resolution was announced from it.
 */
object ScreenOrientationPolicy {

    /** The shape a reading is turned into before anything compares it with another. */
    enum class Normalisation {
        /** Longest side first. */
        LANDSCAPE,
        /** Shortest side first. */
        PORTRAIT,
        /** Neither is known, so the reading is left as it came. */
        AS_READ,
    }

    /** AUTO and SYSTEM take the orientation the configuration is actually in. */
    fun normalisation(
        setting: Settings.ScreenOrientation,
        configLandscape: Boolean,
        configPortrait: Boolean,
    ): Normalisation = when (setting) {
        Settings.ScreenOrientation.LANDSCAPE,
        Settings.ScreenOrientation.LANDSCAPE_REVERSE -> Normalisation.LANDSCAPE
        Settings.ScreenOrientation.PORTRAIT,
        Settings.ScreenOrientation.PORTRAIT_REVERSE -> Normalisation.PORTRAIT
        Settings.ScreenOrientation.AUTO,
        Settings.ScreenOrientation.SYSTEM -> when {
            configLandscape -> Normalisation.LANDSCAPE
            configPortrait -> Normalisation.PORTRAIT
            else -> Normalisation.AS_READ
        }
    }

    /** A reading turned the way [normalisation] says. */
    fun normalise(w: Int, h: Int, normalisation: Normalisation): ProjectionCanvasPolicy.Rect =
        when (normalisation) {
            Normalisation.LANDSCAPE -> ProjectionCanvasPolicy.Rect(Math.max(w, h), Math.min(w, h))
            Normalisation.PORTRAIT -> ProjectionCanvasPolicy.Rect(Math.min(w, h), Math.max(w, h))
            Normalisation.AS_READ -> ProjectionCanvasPolicy.Rect(w, h)
        }
}
