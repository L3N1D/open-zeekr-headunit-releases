package com.lenid.zoh.carplay
import org.junit.Assert.*
import org.junit.Test
class CarPlayViewportTest {
    @Test fun widgetSizesSelectNegotiatedAreasOnTheSameCanvas() {
        val canvas = CarPlayViewport(1920,1080)
        val narrow = CarPlayViewport(1275,876).fitIn(canvas)
        val wide = CarPlayViewport(1796,876).fitIn(canvas)
        val areas = listOf(canvas,narrow,wide)
        repeat(20) {
            assertEquals(1,CarPlayViewport(1275,876).closest(areas))
            assertEquals(2,CarPlayViewport(1796,876).closest(areas))
            assertEquals(0,canvas.closest(areas))
        }
        assertEquals(CarPlayViewport(1920,1080),canvas)
    }
    @Test fun fitPreservesAspectWithoutExceedingCanvas() {
        val original = CarPlayViewport(1796,876)
        val fitted = original.fitIn(CarPlayViewport(1920,1080))
        assertTrue(fitted.width <= 1920 && fitted.height <= 1080)
        assertEquals(original.aspect,fitted.aspect,0.002)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsZeroSizedWindow() { CarPlayViewport(0,876) }
}
