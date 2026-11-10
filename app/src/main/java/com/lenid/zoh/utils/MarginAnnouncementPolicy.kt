package com.lenid.zoh.utils

/**
 * Whether the margins the phone was told about still describe the panel. init() re-reads the
 * display metrics on every scale update, so a device whose window insets are still settling can
 * leave one margin on the wire and a different one in use, with nothing to reconcile them.
 */
object MarginAnnouncementPolicy {

    /** Nothing has been announced yet, so there is nothing to have drifted from. */
    const val NOT_ANNOUNCED = -1

    fun shouldReannounce(
        announcedWidthMargin: Int,
        announcedHeightMargin: Int,
        liveWidthMargin: Int,
        liveHeightMargin: Int
    ): Boolean {
        if (announcedWidthMargin == NOT_ANNOUNCED || announcedHeightMargin == NOT_ANNOUNCED) return false
        return announcedWidthMargin != liveWidthMargin || announcedHeightMargin != liveHeightMargin
    }
}
