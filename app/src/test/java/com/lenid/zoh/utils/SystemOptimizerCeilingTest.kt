package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recommendation and the hard cap are different questions, and conflating them silently
 * overrode a resolution the user had picked through the settings dialog's "Use anyway".
 */
class SystemOptimizerCeilingTest {

    private fun recommended(w: Int, h: Int) = SystemOptimizer.panelCeiling(w, h, canHevc = false)
    private fun hard(w: Int, h: Int) = SystemOptimizer.hardCeiling(w, h, canHevc = false)

    // The reported panel. AUTO should ask for 720p and let the pixel ratio carry the width, but a
    // 1920-wide buffer is fully used here: every column lands on a column, only rows are hidden.
    @Test
    fun `an ultra-wide panel is recommended 720p and still allowed 1080p`() {
        assertEquals(Settings.Resolution._1280x720, recommended(1920, 720))
        assertEquals(Settings.Resolution._1920x1080, hard(1920, 720))
    }

    @Test
    fun `the rig panel and the other reported 720-row panels behave the same way`() {
        for ((w, h) in listOf(1440 to 720, 1780 to 720)) {
            assertEquals(Settings.Resolution._1280x720, recommended(w, h))
            assertEquals(Settings.Resolution._1920x1080, hard(w, h))
        }
    }

    // A wide panel with more than 720 rows is not in the margin-free class at all: the ladder
    // already asks for 1080p, so the recommendation and the cap have nothing to disagree about.
    @Test
    fun `a wide panel with more than 720 rows was never capped`() {
        assertEquals(Settings.Resolution._1920x1080, recommended(2400, 900))
        assertEquals(Settings.Resolution._1920x1080, hard(2400, 900))
    }

    // The panel issue #650 is about. Both answers stay 720p, so the per-frame downscale that
    // stalls the MediaTek scaler is still refused however the resolution was chosen.
    @Test
    fun `a genuinely small panel is capped by both`() {
        assertEquals(Settings.Resolution._1280x720, recommended(1024, 600))
        assertEquals(Settings.Resolution._1280x720, hard(1024, 600))
        assertEquals(Settings.Resolution._800x480, recommended(800, 480))
        assertEquals(Settings.Resolution._800x480, hard(800, 480))
    }

    @Test
    fun `a 16 by 9 panel gets the same answer from both`() {
        for ((w, h) in listOf(1280 to 720, 1920 to 1080, 0 to 0)) {
            assertEquals(recommended(w, h), hard(w, h))
        }
    }

    // A cap below the recommendation would undo AUTO's own choice on the next connect.
    @Test
    fun `the hard ceiling never sits below the recommendation`() {
        for (w in listOf(0, 480, 800, 1024, 1280, 1440, 1780, 1920, 2400, 2560, 3840)) {
            for (h in listOf(0, 480, 600, 720, 800, 900, 1080, 1440)) {
                val rec = recommended(w, h)
                val cap = hard(w, h)
                assertTrue(
                    "hard ceiling ${cap.resName} below recommended ${rec.resName} for ${w}x$h",
                    cap.width * cap.height >= rec.width * rec.height
                )
            }
        }
    }
}
