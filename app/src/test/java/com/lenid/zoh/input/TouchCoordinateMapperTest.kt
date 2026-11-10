package com.lenid.zoh.input

import com.lenid.zoh.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordinateMapperTest {

    @Test
    fun `touch mapping uses actual input surface width`() {
        val point = TouchCoordinateMapper.map(
            rawX = 1200f,
            rawY = 540f,
            inputSurfaceWidth = 2400f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 0f,
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = false
        )

        assertEquals(960, point.x)
        assertEquals(540, point.y)
    }

    // Android Auto anchors its canvas at the buffer's top-left with the announced margin at the
    // bottom, measured on hardware from the coordinate it logged receiving. Centring this mapping
    // instead cost 135 panel px on a 2400x1080 unit and hit the control one rail slot below.
    @Test
    fun `a margined canvas is anchored at the buffer top-left`() {
        val point = TouchCoordinateMapper.map(
            rawX = 1200f,
            rawY = 540f,
            inputSurfaceWidth = 2400f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 194f,
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = false
        )

        assertEquals(960, point.x)
        assertEquals(443, point.y)
    }

    // #809 in Screen mode Normal: the events come from an overlay the ROM sidebar narrowed, while
    // the screen config still described the display. Dividing by the config figure sent a tap on
    // the bottom row to the row below the buffer and every tap left of where it landed.
    @Test
    fun `the denominator is the surface the events came from`() {
        fun at(surfaceW: Float, surfaceH: Float) = TouchCoordinateMapper.map(
            rawX = 1740f,
            rawY = 715f,
            inputSurfaceWidth = surfaceW,
            inputSurfaceHeight = surfaceH,
            negotiatedWidth = 1280,
            negotiatedHeight = 720,
            marginWidth = 0f,
            marginHeight = 0f,
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = false
        )

        val measured = at(1748f, 720f)
        assertEquals(1274, measured.x)
        assertEquals(715, measured.y)

        val fromDisplayMetrics = at(1920f, 642f)
        assertEquals(1160, fromDisplayMetrics.x)
        assertEquals(720, fromDisplayMetrics.y)
    }

    // The panel the ultra-wide work is measured on: a 1280x720 buffer with no margin at all, so
    // the mapping is the panel scaled straight onto the buffer.
    @Test
    fun `an ultra-wide panel maps onto the whole buffer`() {
        fun at(x: Float, y: Float) = TouchCoordinateMapper.map(
            rawX = x,
            rawY = y,
            inputSurfaceWidth = 1920f,
            inputSurfaceHeight = 720f,
            negotiatedWidth = 1280,
            negotiatedHeight = 720,
            marginWidth = 0f,
            marginHeight = 0f,
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = false
        )

        assertEquals(0, at(0f, 0f).x)
        assertEquals(0, at(0f, 0f).y)
        assertEquals(640, at(960f, 360f).x)
        assertEquals(360, at(960f, 360f).y)
        assertEquals(1280, at(1920f, 720f).x)
        assertEquals(720, at(1920f, 720f).y)
    }

    @Test
    fun `touch mapping mirrors horizontal coordinates for hud mirroring`() {
        val point = TouchCoordinateMapper.map(
            rawX = 240f,
            rawY = 540f,
            inputSurfaceWidth = 2400f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 0f,
            fitMode = Settings.VideoFitMode.FILL,
            hudMirroring = true
        )

        assertEquals(1728, point.x)
        assertEquals(540, point.y)
    }

    @Test
    fun `contain mode clamps touches in the letterbox bar to the video edge`() {
        // Surface is 2560x1080 (wider than the 1920x1080 video); CONTAIN pillarboxes it to
        // 1920x1080 centered, leaving 320px bars on each side. A touch at the surface's own
        // top-left corner lands inside the left bar, which should clamp to video x=0.
        val point = TouchCoordinateMapper.map(
            rawX = 0f,
            rawY = 0f,
            inputSurfaceWidth = 2560f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 0f,
            fitMode = Settings.VideoFitMode.CONTAIN,
            hudMirroring = false
        )

        assertEquals(0, point.x)
        assertEquals(0, point.y)
    }

    @Test
    fun `cover mode crops instead of bars so the same corner maps into cropped video content`() {
        // Same 2560x1080 surface and 1920x1080 video as above, but COVER crops instead of
        // bars: it scales up to 2560x1440 (cropping 180px off the top and bottom), so the
        // surface's top-left corner is 135 video-pixels below the video's actual top edge.
        val point = TouchCoordinateMapper.map(
            rawX = 0f,
            rawY = 0f,
            inputSurfaceWidth = 2560f,
            inputSurfaceHeight = 1080f,
            negotiatedWidth = 1920,
            negotiatedHeight = 1080,
            marginWidth = 0f,
            marginHeight = 0f,
            fitMode = Settings.VideoFitMode.COVER,
            hudMirroring = false
        )

        assertEquals(0, point.x)
        assertEquals(135, point.y)
    }
}
