package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The panels are the ones this was measured or reported on: a 1920x720 reporter unit (1780 and
 * 640 usable with its bars), the 1440x720 rig unit, 2400x1080 rig phones, and a 1024x600 unit.
 */
class MarginStrategyPolicyTest {

    private val fill = Settings.VideoFitMode.FILL
    private val contain = Settings.VideoFitMode.CONTAIN
    private val cover = Settings.VideoFitMode.COVER
    private val par = MarginStrategyPolicy.Strategy.PAR
    private val margin = MarginStrategyPolicy.Strategy.MARGIN

    private fun select(mode: Settings.VideoFitMode, panelW: Int, panelH: Int, videoW: Int, videoH: Int) =
        MarginStrategyPolicy.select(mode, panelW, panelH, videoW, videoH)

    private fun margins(panelW: Int, panelH: Int, videoW: Int, videoH: Int): Pair<Int, Int> {
        val sf = ProjectionGeometryPolicy.fit(panelW, panelH, videoW, videoH).scaleFactor
        return ProjectionGeometryPolicy.widthMargin(videoW, panelW, sf) to
            ProjectionGeometryPolicy.heightMargin(videoH, panelH, sf)
    }

    // The stored resolution used to decide this: 720p on the reporter panel was already margin-free
    // and 1080p hid a third of the frame. Both rungs now describe the panel the same way.
    @Test
    fun `fill on a panel wider than its buffer rides on the pixel ratio at any resolution`() {
        assertEquals(par, select(fill, 1920, 720, 1920, 1080))
        assertEquals(par, select(fill, 1920, 720, 1280, 720))
        assertEquals(par, select(fill, 1780, 720, 1280, 720))
        assertEquals(par, select(fill, 1920, 640, 1920, 1080))
        assertEquals(par, select(fill, 1440, 720, 1920, 1080))
        assertEquals(par, select(fill, 2400, 900, 1920, 1080))
        assertEquals(par, select(fill, 2400, 1080, 1920, 1080))
    }

    @Test
    fun `a 16 by 9 panel keeps the margin path and its margin is zero`() {
        assertEquals(margin, select(fill, 1920, 1080, 1920, 1080))
        assertEquals(0 to 0, margins(1920, 1080, 1920, 1080))
        assertEquals(margin, select(fill, 1280, 720, 1280, 720))
    }

    // Below square the ratio has field evidence only from a phone, so that direction keeps the
    // margin until a round on such a panel says otherwise.
    @Test
    fun `a panel taller than its buffer keeps the margin`() {
        assertEquals(margin, select(fill, 1024, 600, 1280, 720))
        assertEquals(margin, select(fill, 1080, 2400, 1080, 1920))
    }

    @Test
    fun `contain and cover keep the margin`() {
        assertEquals(margin, select(contain, 1920, 720, 1920, 1080))
        assertEquals(margin, select(cover, 1920, 720, 1920, 1080))
        assertEquals(margin, select(contain, 1920, 720, 1280, 720))
    }

    // A 1920x480 bar display derives 22500, past what the ratio is allowed to carry.
    @Test
    fun `a shape past the clamp keeps the margin`() {
        assertEquals(margin, select(fill, 1920, 480, 1280, 720))
        assertEquals(margin, select(fill, 4000, 200, 1280, 720))
    }

    @Test
    fun `a degenerate size keeps the margin`() {
        assertEquals(margin, select(fill, 0, 720, 1280, 720))
        assertEquals(margin, select(fill, 1920, 0, 1280, 720))
        assertEquals(margin, select(fill, 1920, 720, 0, 0))
    }

    // The whole chain at the rung the wizard stores on these panels. With the margin gone every
    // scale is 1.0, so the buffer, the picture and the touch surface coincide, and the panel's shape
    // rides on the ratio exactly as it does at 720p.
    @Test
    fun `1080p on a wide panel needs no margin and no transform`() {
        for ((panelW, expectedPar) in listOf(1920 to 15000, 1440 to 11250)) {
            assertEquals(par, select(fill, panelW, 720, 1920, 1080))
            assertEquals(1.0f, ProjectionGeometryPolicy.scaleX(fill, false, panelW, 720, 1920, 1080, 0, 0), 0.0001f)
            assertEquals(1.0f, ProjectionGeometryPolicy.scaleY(fill, false, panelW, 720, 1920, 1080, 0, 0), 0.0001f)
            assertEquals(expectedPar, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, panelW, 720, 1920, 1080, 0, 0))
        }
        assertEquals(12500, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 2400, 1080, 1920, 1080, 0, 0))
        assertEquals(16875, ProjectionGeometryPolicy.pixelAspectRatioE4(fill, 1920, 640, 1920, 1080, 0, 0))
    }
}
