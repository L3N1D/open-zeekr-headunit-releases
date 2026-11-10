package com.lenid.zoh.utils

/**
 * Resolves the stored video fit mode, migrating installs that only have the old
 * "stretch_to_fill" boolean. The legacy forcedScale/SurfaceView path read that boolean
 * inverted (true meant bars, not stretch), so the migration branches on it.
 */
object VideoFitPolicy {

    /** Null arguments mean the preference was never written; the old getter read the boolean as true. */
    fun resolve(
        storedFitMode: Int?,
        legacyStretch: Boolean?,
        legacyForcedScale: Boolean,
        legacyViewMode: Int
    ): Settings.VideoFitMode {
        if (storedFitMode != null) {
            return Settings.VideoFitMode.fromInt(storedFitMode) ?: Settings.VideoFitMode.FILL
        }
        val legacy = legacyStretch ?: true
        val forcedScaleActive = legacyForcedScale && legacyViewMode == Settings.ViewMode.SURFACE.value
        val effectiveStretch = if (forcedScaleActive) !legacy else legacy
        return if (effectiveStretch) Settings.VideoFitMode.FILL else Settings.VideoFitMode.CONTAIN
    }
}
