package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationPolicyTest {

    private fun normalisation(
        setting: Settings.ScreenOrientation,
        landscape: Boolean = false,
        portrait: Boolean = false,
    ) = ScreenOrientationPolicy.normalisation(setting, landscape, portrait)

    @Test
    fun `an explicit orientation ignores what the configuration says`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            normalisation(Settings.ScreenOrientation.LANDSCAPE, portrait = true)
        )
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(Settings.ScreenOrientation.PORTRAIT, landscape = true)
        )
    }

    @Test
    fun `a reversed orientation normalises like its forward twin`() {
        assertEquals(
            normalisation(Settings.ScreenOrientation.LANDSCAPE),
            normalisation(Settings.ScreenOrientation.LANDSCAPE_REVERSE)
        )
        assertEquals(
            normalisation(Settings.ScreenOrientation.PORTRAIT),
            normalisation(Settings.ScreenOrientation.PORTRAIT_REVERSE)
        )
    }

    // The rig runs AUTO/SYSTEM, and the surface path used to omit this fallback entirely: it took
    // the four explicit settings only, so on the rig every surface reading was left as it came.
    @Test
    fun `auto and system take the orientation the configuration is in`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.LANDSCAPE,
            normalisation(Settings.ScreenOrientation.AUTO, landscape = true)
        )
        assertEquals(
            ScreenOrientationPolicy.Normalisation.PORTRAIT,
            normalisation(Settings.ScreenOrientation.SYSTEM, portrait = true)
        )
    }

    @Test
    fun `a configuration in neither orientation leaves the reading alone`() {
        assertEquals(
            ScreenOrientationPolicy.Normalisation.AS_READ,
            normalisation(Settings.ScreenOrientation.AUTO)
        )
        val read = ScreenOrientationPolicy.normalise(1080, 2400, ScreenOrientationPolicy.Normalisation.AS_READ)
        assertEquals(1080, read.width)
        assertEquals(2400, read.height)
    }

    // Measured: a 2400x1080 phone reported its window content as the portrait 1080x2400 for 61 ms
    // and the head unit announced a portrait resolution from it.
    @Test
    fun `a portrait window reading becomes the landscape canvas`() {
        val turned = ScreenOrientationPolicy.normalise(1080, 2400, ScreenOrientationPolicy.Normalisation.LANDSCAPE)
        assertEquals(2400, turned.width)
        assertEquals(1080, turned.height)
    }

    @Test
    fun `a reading already the right way up is left as it is`() {
        val turned = ScreenOrientationPolicy.normalise(1440, 720, ScreenOrientationPolicy.Normalisation.LANDSCAPE)
        assertEquals(1440, turned.width)
        assertEquals(720, turned.height)
    }
}
