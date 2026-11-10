package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZeekrCardProjectionPolicyTest {
    @Test
    fun `reference 1080p margins match the verified car screenshots`() {
        assertEquals(645 to 204, ZeekrCardProjectionPolicy.margins(1275, 876, 1920, 1080))
        assertEquals(0 to 144, ZeekrCardProjectionPolicy.margins(1796, 876, 1920, 1080))
    }

    @Test
    fun `720p margins preserve the same scale without raising the link profile`() {
        assertEquals(430 to 136, ZeekrCardProjectionPolicy.margins(1275, 876, 1280, 720))
        assertEquals(0 to 96, ZeekrCardProjectionPolicy.margins(1796, 876, 1280, 720))

        assertEquals(1.5058824f, 1280f / 850f, 0.00001f)
        assertEquals(1.2328767f, 720f / 584f, 0.00001f)
        assertEquals(1.0f, 1280f / 1280f, 0.00001f)
        assertEquals(1.1538461f, 720f / 624f, 0.00001f)
    }

    @Test
    fun `connecting in either card uses the same legible session density`() {
        assertEquals(1920 to 936, ZeekrCardProjectionPolicy.densityCanvas(1275, 876))
        assertEquals(1920 to 936, ZeekrCardProjectionPolicy.densityCanvas(1796, 876))
        assertEquals(216, ZeekrCardProjectionPolicy.scaledAutomaticDensity(216, 1920, 1080))
        assertEquals(144, ZeekrCardProjectionPolicy.scaledAutomaticDensity(216, 1280, 720))
        for (panelW in listOf(1275, 1796)) {
            val canvas = ZeekrCardProjectionPolicy.densityCanvas(panelW, 876)!!
            val density = AnnouncedDensityPolicy.decide(
                0, SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS, canvas.first, canvas.second,
            ).announced
            assertEquals(216, density)
        }
    }

    @Test
    fun `card transitions preserve the pixel shape from a fullscreen session`() {
        for (par in listOf(10000, 12054)) {
            for ((videoW, videoH) in listOf(1920 to 1080, 1280 to 720)) {
                for (panelW in listOf(1275, 1796, 1275)) {
                    val margins = ZeekrCardProjectionPolicy.margins(panelW, 876, videoW, videoH, par)!!
                    val canvasW = videoW - margins.first
                    val canvasH = videoH - margins.second
                    val renderedPixelShape = panelW.toFloat() * canvasH / (876f * canvasW)
                    assertEquals(par / 10000f, renderedPixelShape, 0.002f)
                    org.junit.Assert.assertTrue(canvasW in 1..videoW && canvasH in 1..videoH)
                }
            }
        }
    }

    @Test
    fun `full screen and unrelated panels are untouched`() {
        assertNull(ZeekrCardProjectionPolicy.margins(1920, 1080, 1920, 1080))
        assertNull(ZeekrCardProjectionPolicy.margins(1280, 720, 1280, 720))
        assertNull(ZeekrCardProjectionPolicy.margins(1275, 876, 800, 480))
    }
}
