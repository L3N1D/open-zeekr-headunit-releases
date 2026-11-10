package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are the two panels this was measured on: a 1440x720 rig unit and a 1920x720 reporter
 * unit, plus the 1780x720 usable area the same unit reports with its sidebar showing.
 */
class ProjectionGeometryPolicyTest {

    private val fill = Settings.VideoFitMode.FILL
    private val contain = Settings.VideoFitMode.CONTAIN
    private val cover = Settings.VideoFitMode.COVER

    private fun scaleY(
        mode: Settings.VideoFitMode,
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int,
        marginW: Int = 0,
        marginH: Int = 0
    ) = ProjectionGeometryPolicy.scaleY(mode, false, panelW, panelH, videoW, videoH, marginW, marginH)

    private fun scaleX(
        mode: Settings.VideoFitMode,
        panelW: Int,
        panelH: Int,
        videoW: Int,
        videoH: Int,
        marginW: Int = 0,
        marginH: Int = 0
    ) = ProjectionGeometryPolicy.scaleX(mode, false, panelW, panelH, videoW, videoH, marginW, marginH)

    /** The margins a panel/video pair actually produces, so no fixture invents its own. */
    private fun margins(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Pair<Int, Int> {
        val sf = ProjectionGeometryPolicy.fit(panelW, panelH, videoW, videoH).scaleFactor
        return ProjectionGeometryPolicy.widthMargin(videoW, panelW, sf) to
            ProjectionGeometryPolicy.heightMargin(videoH, panelH, sf)
    }

    // The regression. Before the fit modes, getScaleY() fell through to panel aspect over video
    // aspect whenever the negotiated height already fit, blowing the picture up and cropping it.
    @Test
    fun `720p on an ultra-wide panel is not stretched vertically`() {
        assertEquals(1.0f, scaleY(fill, 1920, 720, 1280, 720), 0.0001f)
        assertEquals(1.0f, scaleY(fill, 1440, 720, 1280, 720), 0.0001f)
        assertEquals(1.0f, scaleY(fill, 1780, 720, 1280, 720), 0.0001f)
        assertEquals(1.0f, scaleX(fill, 1920, 720, 1280, 720), 0.0001f)
    }

    @Test
    fun `a 16 by 9 panel at its own resolution is untouched`() {
        assertEquals(1.0f, scaleY(fill, 1920, 1080, 1920, 1080), 0.0001f)
        assertEquals(1.0f, scaleX(fill, 1920, 1080, 1920, 1080), 0.0001f)
        assertEquals(0, ProjectionGeometryPolicy.heightMargin(1080, 1080, 1.0f))
        assertEquals(0, ProjectionGeometryPolicy.widthMargin(1920, 1920, 1.0f))
    }

    // 1080p into a 1440x720 panel is the case that already worked: the margin mechanism describes
    // the usable canvas inside the bigger buffer, and both axes still scale up to reach it.
    @Test
    fun `1080p on the rig panel keeps its margins and its scale`() {
        assertEquals(480, ProjectionGeometryPolicy.widthMargin(1920, 1440, 1.0f))
        assertEquals(360, ProjectionGeometryPolicy.heightMargin(1080, 720, 1.0f))
        assertEquals(1.3333f, scaleX(fill, 1440, 720, 1920, 1080, 480, 360), 0.0001f)
        assertEquals(1.5f, scaleY(fill, 1440, 720, 1920, 1080, 480, 360), 0.0001f)
    }

    // A panel wider than 1920 leaves the small-screen bucket, so fit() scales the buffer up to the
    // panel width and the leftover height becomes a margin. Returning 1.0f there deleted the crop
    // that makes that margin true and squashed the picture by a fifth. Measured on two 2400x1080
    // phones: 0x144 at 720p and 0x216 at 1080p.
    @Test
    fun `a panel wider than 1920 crops the margin instead of showing it`() {
        assertEquals(0 to 144, margins(2400, 1080, 1280, 720))
        assertEquals(1.0f, scaleX(fill, 2400, 1080, 1280, 720, 0, 144), 0.0001f)
        assertEquals(1.25f, scaleY(fill, 2400, 1080, 1280, 720, 0, 144), 0.0001f)

        assertEquals(0 to 216, margins(2400, 1080, 1920, 1080))
        assertEquals(1.0f, scaleX(fill, 2400, 1080, 1920, 1080, 0, 216), 0.0001f)
        assertEquals(1.25f, scaleY(fill, 2400, 1080, 1920, 1080, 0, 216), 0.0001f)
    }

    // The whole rule, on every panel measured. A margin of zero is the only case that leaves the
    // buffer alone, which is why the ultra-wide 720p fix and this crop are the same expression.
    @Test
    fun `the fill scale is always the buffer over the visible canvas`() {
        val cases = listOf(
            intArrayOf(1440, 720, 1280, 720),
            intArrayOf(1920, 720, 1280, 720),
            intArrayOf(1440, 720, 1920, 1080),
            intArrayOf(2400, 1080, 1280, 720),
            intArrayOf(2400, 1080, 1920, 1080),
            intArrayOf(1200, 2000, 1280, 720)
        )
        for (c in cases) {
            val (panelW, panelH, videoW, videoH) = listOf(c[0], c[1], c[2], c[3])
            val (mW, mH) = margins(panelW, panelH, videoW, videoH)
            val label = "${panelW}x$panelH <- ${videoW}x$videoH"
            assertEquals(
                label, videoW.toFloat() / (videoW - mW), scaleX(fill, panelW, panelH, videoW, videoH, mW, mH), 0.0001f
            )
            assertEquals(
                label, videoH.toFloat() / (videoH - mH), scaleY(fill, panelW, panelH, videoW, videoH, mW, mH), 0.0001f
            )
        }
    }

    // The portrait fit branch used to have its own arm. Substituting the margin reproduces it,
    // which is the evidence the one expression is the rule the file was written against.
    @Test
    fun `a portrait panel showing a landscape buffer is unchanged`() {
        val (mW, mH) = margins(1200, 2000, 1280, 720)
        assertEquals(848 to 0, mW to mH)
        assertEquals(2.963f, scaleX(fill, 1200, 2000, 1280, 720, mW, mH), 0.001f)
        assertEquals(1.0f, scaleY(fill, 1200, 2000, 1280, 720, mW, mH), 0.0001f)
    }

    @Test
    fun `1080p on the reporter panel is pillarbox-free and letterboxed`() {
        assertEquals(0, ProjectionGeometryPolicy.widthMargin(1920, 1920, 1.0f))
        assertEquals(360, ProjectionGeometryPolicy.heightMargin(1080, 720, 1.0f))
    }

    @Test
    fun `contain shrinks the wider axis and leaves the other alone`() {
        // min(1440/1280, 720/720) = 1.0, so only the x axis comes back below 1: a pillarbox.
        assertEquals(0.8889f, scaleX(contain, 1440, 720, 1280, 720), 0.0001f)
        assertEquals(1.0f, scaleY(contain, 1440, 720, 1280, 720), 0.0001f)
    }

    @Test
    fun `cover fills the wider axis and crops the other`() {
        // max(1440/1280, 720/720) = 1.125, so x reaches exactly 1 and y overshoots and is cropped.
        assertEquals(1.0f, scaleX(cover, 1440, 720, 1280, 720), 0.0001f)
        assertEquals(1.125f, scaleY(cover, 1440, 720, 1280, 720), 0.0001f)
    }

    @Test
    fun `cover never downscales a video that already has the pixels`() {
        assertEquals(1.0f, ProjectionGeometryPolicy.coverScaleFactor(1440, 720, 1920, 1080), 0.0001f)
        assertEquals(1920, ProjectionGeometryPolicy.coverWidth(1440, 720, 1920, 1080))
        assertEquals(1080, ProjectionGeometryPolicy.coverHeight(1440, 720, 1920, 1080))
    }

    // A margin that already shapes the canvas to the panel leaves nothing to letterbox or crop, so
    // both modes collapse onto FILL. TouchCoordinateMapper reaches the same verdict from the same
    // canvas, which is what keeps a tap in a bar it can no longer be in.
    @Test
    fun `contain and cover measure the canvas, not the buffer`() {
        for (mode in listOf(contain, cover)) {
            assertEquals(1.0f, scaleX(mode, 2400, 1080, 1280, 720, 0, 144), 0.0001f)
            assertEquals(1.25f, scaleY(mode, 2400, 1080, 1280, 720, 0, 144), 0.0001f)
        }
    }

    @Test
    fun `the legacy forcedScale path scales neither axis`() {
        for (mode in Settings.VideoFitMode.values()) {
            assertEquals(1.0f, ProjectionGeometryPolicy.scaleX(mode, true, 1920, 720, 1280, 720, 0, 0), 0.0001f)
            assertEquals(1.0f, ProjectionGeometryPolicy.scaleY(mode, true, 1920, 720, 1280, 720, 0, 0), 0.0001f)
        }
    }

    @Test
    fun `both real panels count as small so nothing rescales the buffer`() {
        assertFalse(ProjectionGeometryPolicy.isSmallScreen(2400, 1080))
        assertTrue(ProjectionGeometryPolicy.isSmallScreen(1440, 720))
        assertTrue(ProjectionGeometryPolicy.isSmallScreen(1920, 720))
        assertTrue(ProjectionGeometryPolicy.isSmallScreen(1920, 1080))
        assertFalse(ProjectionGeometryPolicy.isSmallScreen(2560, 1440))
    }

    // A small screen leaves isPortraitScaled untouched rather than recomputing it, which is why the
    // policy reports null instead of a value the caller would latch.
    @Test
    fun `a small screen reports no portrait-scaled verdict`() {
        val small = ProjectionGeometryPolicy.fit(1440, 720, 1280, 720)
        assertEquals(1.0f, small.scaleFactor, 0.0001f)
        assertTrue(small.isSmallScreen)
        assertNull(small.isPortraitScaled)

        val large = ProjectionGeometryPolicy.fit(2560, 1440, 1920, 1080)
        assertEquals(1.3333f, large.scaleFactor, 0.0001f)
        assertFalse(large.isSmallScreen)
        assertEquals(false, large.isPortraitScaled)
    }

    @Test
    fun `a degenerate size never divides by zero`() {
        assertEquals(1.0f, ProjectionGeometryPolicy.fit(1440, 720, 0, 0).scaleFactor, 0.0001f)
        assertEquals(0, ProjectionGeometryPolicy.widthMargin(1280, 1440, 0.0f))
    }

    // Telling the phone the pixels are not square is the only lever a non-16:9 panel has, because
    // the proto offers no non-16:9 resolution to ask for. Measured on a 1440x720 panel: the derived
    // 11250 drew Android Auto's chrome circles 60x60 px, square 10001 drew them 67x60. What is left
    // on the panel is derived/announced, so the derived value is the one that cancels.
    @Test
    fun `a wide panel at 720p advertises its real pixel shape`() {
        assertEquals(15000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1920, 720, 1280, 720, 0, 0))
        assertEquals(11250, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1440, 720, 1280, 720, 0, 0))
        assertEquals(13906, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1780, 720, 1280, 720, 0, 0))
    }

    @Test
    fun `a 16 by 9 panel stays square`() {
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1920, 1080, 1920, 1080, 0, 0))
    }

    // The margins already describe a correctly shaped canvas inside the bigger buffer, so measuring
    // against the raw negotiated size would correct a second time and skew the case that works.
    @Test
    fun `a margined canvas is already square and is left alone`() {
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1440, 720, 1920, 1080, 480, 360))
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1920, 720, 1920, 1080, 0, 360))
    }

    // The whole chain on the panel this was reported from, with the resolution the short-side
    // ladder now picks for it. Every scale is 1.0 and there is no margin, so the buffer, the
    // picture and the touch surface all coincide, and the panel's shape rides on the pixel ratio.
    @Test
    fun `an ultra-wide panel needs no margin and no transform`() {
        for ((panelW, expectedPar) in listOf(1920 to 15000, 1780 to 13906)) {
            assertEquals(0 to 0, margins(panelW, 720, 1280, 720))
            assertEquals(1.0f, scaleX(fill, panelW, 720, 1280, 720), 0.0001f)
            assertEquals(1.0f, scaleY(fill, panelW, 720, 1280, 720), 0.0001f)
            assertEquals(
                expectedPar,
                ProjectionGeometryPolicy.pixelAspectRatioE4(fill, panelW, 720, 1280, 720, 0, 0)
            )
        }
    }

    // An impossible panel reading is a bad measurement, not a panel. Say square rather than put a
    // number on the wire that no phone can lay a UI out for.
    @Test
    fun `an impossible panel cannot put nonsense on the wire`() {
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 4000, 200, 1280, 720, 0, 0))
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 200, 4000, 1280, 720, 0, 0))
    }

    @Test
    fun `a near-square panel is not worth correcting`() {
        // 1940x1080 derives 10104, which is 1 percent off square and inside the tolerance.
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1940, 1080, 1920, 1080, 0, 0))
    }

    // The correction and the two aspect-preserving modes are alternatives, not layers: announcing
    // 11250 and then pillarboxing with square pixels squeezes the phone's UI by the correction.
    @Test
    fun `only fill claims the pixels are not square`() {
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(contain, 1920, 720, 1280, 720, 0, 0))
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(cover, 1920, 720, 1280, 720, 0, 0))
        assertEquals(15000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1920, 720, 1280, 720, 0, 0))
    }

    @Test
    fun `a degenerate canvas reports square pixels`() {
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1920, 720, 1280, 720, 1280, 0))
        assertEquals(10000, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 0, 0, 1280, 720, 0, 0))
    }

    @Test
    fun `zeekr card resize preserves the announced pixel shape in both directions`() {
        val compactPar = ProjectionGeometryPolicy.pixelAspectRatioE4(
            fill, 1275, 876, 1280, 720, 5, 0
        )
        assertEquals(8219, compactPar)

        assertEquals(
            0 to 207,
            ProjectionGeometryPolicy.sessionCompensatedMargins(1796, 876, 1280, 720, compactPar)
        )
        assertEquals(
            5 to 0,
            ProjectionGeometryPolicy.sessionCompensatedMargins(1275, 876, 1280, 720, compactPar)
        )
    }

    @Test
    fun `an unchanged wide card needs no session compensation`() {
        val expandedPar = ProjectionGeometryPolicy.pixelAspectRatioE4(
            fill, 1796, 876, 1280, 720, 0, 0
        )
        assertNull(
            ProjectionGeometryPolicy.sessionCompensatedMargins(1796, 876, 1280, 720, expandedPar)
        )
    }
}
