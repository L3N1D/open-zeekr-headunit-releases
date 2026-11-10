package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NegotiatedResolutionPolicyTest {

    private fun select(
        locked: Boolean,
        selected: Settings.Resolution?,
        panelW: Int = 1440,
        panelH: Int = 720,
        fitMode: Settings.VideoFitMode = Settings.VideoFitMode.FILL,
        hevc: Boolean = true,
        canHevcHighRes: Boolean = true,
        sdkInt: Int = 30
    ) = NegotiatedResolutionPolicy.select(locked, selected, panelW, panelH, fitMode, hevc, canHevcHighRes, sdkInt)

    // The bug: a locked session fell through to the manual branch, where AUTO has no codec and the
    // fallback is 480p, so it silently downgraded itself mid-drive.
    @Test
    fun `a locked session is never renegotiated`() {
        assertNull(select(locked = true, selected = Settings.Resolution.AUTO))
        assertNull(select(locked = true, selected = Settings.Resolution._1280x720))
        assertNull(select(locked = true, selected = null))
    }

    @Test
    fun `an unlocked manual choice is taken as given`() {
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution._1920x1080))
        assertEquals(Settings.Resolution._800x480, select(false, Settings.Resolution._800x480))
    }

    // A corrupt resolutionId reads back as null. That used to mean 480p; it now means AUTO.
    @Test
    fun `an unreadable stored resolution falls into the AUTO ladder`() {
        assertEquals(Settings.Resolution._1280x720, select(false, null, panelW = 1440, panelH = 720))
    }

    @Test
    fun `the AUTO ladder picks by the panel's short side`() {
        assertEquals(Settings.Resolution._800x480, select(false, Settings.Resolution.AUTO, 800, 480))
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1280, 720))
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1920, 1080))
        assertEquals(Settings.Resolution._2560x1440, select(false, Settings.Resolution.AUTO, 2560, 1440))
        assertEquals(Settings.Resolution._3840x2160, select(false, Settings.Resolution.AUTO, 3840, 2160))
    }

    // Asking a 720-row panel for 1080 rows only buys a height margin that hides them again, and
    // the margin is what cancelled the pixel aspect ratio back to square. These are the panels the
    // short-side rule changes, and every one of them is wider than 16:9.
    @Test
    fun `an ultra-wide panel is asked for the rows it actually has`() {
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1920, 720))
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1780, 720))
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1440, 720))
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 2560, 720))
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 2560, 1080))
    }

    // CONTAIN and COVER announce square pixels, so the width has to come from the buffer: on a
    // 1920x720 panel 720p is a 1280-wide picture between pillars, and 1080p with a height margin
    // fills the width at 1:1, which is what every release before the short-side rule negotiated.
    @Test
    fun `without the pixel aspect ratio a wide panel needs the columns again`() {
        for (mode in listOf(Settings.VideoFitMode.CONTAIN, Settings.VideoFitMode.COVER)) {
            assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1920, 720, mode))
            assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1440, 720, mode))
            assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1780, 720, mode))
            assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1280, 720, mode))
            assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1024, 600, mode))
            assertEquals(Settings.Resolution._800x480, select(false, Settings.Resolution.AUTO, 800, 480, mode))
            assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1920, 1080, mode))
            assertEquals(Settings.Resolution._2560x1440, select(false, Settings.Resolution.AUTO, 2560, 1440, mode))
        }
    }

    // The regression net for everyone who is fine today: a panel whose rows already match a rung
    // is asked for exactly what it was asked for before.
    @Test
    fun `a panel that matches a rung is left alone`() {
        assertEquals(Settings.Resolution._800x480, select(false, Settings.Resolution.AUTO, 800, 480))
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1024, 600))
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 1280, 480))
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 2400, 1080))
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1280, 800))
    }

    @Test
    fun `the high resolutions need both HEVC and a new enough platform`() {
        assertEquals(
            Settings.Resolution._1920x1080,
            select(false, Settings.Resolution.AUTO, 2560, 1440, canHevcHighRes = false)
        )
        assertEquals(
            Settings.Resolution._1920x1080,
            select(false, Settings.Resolution.AUTO, 3840, 2160, hevc = false, canHevcHighRes = false)
        )
        assertEquals(
            Settings.Resolution._1920x1080,
            select(false, Settings.Resolution.AUTO, 2560, 1440, sdkInt = 23)
        )
    }

    // The short-side rule is orientation-agnostic, so portrait needs no branch of its own:
    // HeadUnitScreenConfig.protoForResolution transposes whichever rung comes back.
    @Test
    fun `a portrait panel lands on the same rung as its landscape twin`() {
        assertEquals(Settings.Resolution._1280x720, select(false, Settings.Resolution.AUTO, 720, 1280))
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1080, 1920))
        assertEquals(Settings.Resolution._1920x1080, select(false, Settings.Resolution.AUTO, 1200, 2000))
    }
}
