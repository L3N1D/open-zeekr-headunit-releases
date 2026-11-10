package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFitPolicyTest {

    private val surface = Settings.ViewMode.SURFACE.value
    private val texture = Settings.ViewMode.TEXTURE.value

    @Test
    fun `a stored mode is used as-is`() {
        assertEquals(
            Settings.VideoFitMode.COVER,
            VideoFitPolicy.resolve(storedFitMode = 2, legacyStretch = null, legacyForcedScale = false, legacyViewMode = texture)
        )
    }

    @Test
    fun `a stored mode that is out of range falls back to FILL`() {
        assertEquals(
            Settings.VideoFitMode.FILL,
            VideoFitPolicy.resolve(storedFitMode = 99, legacyStretch = null, legacyForcedScale = false, legacyViewMode = texture)
        )
    }

    @Test
    fun `a stored mode wins over the legacy boolean`() {
        assertEquals(
            Settings.VideoFitMode.CONTAIN,
            VideoFitPolicy.resolve(storedFitMode = 1, legacyStretch = true, legacyForcedScale = false, legacyViewMode = texture)
        )
    }

    @Test
    fun `a fresh install with no preference at all gets FILL`() {
        assertEquals(
            Settings.VideoFitMode.FILL,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = null, legacyForcedScale = false, legacyViewMode = texture)
        )
    }

    @Test
    fun `off the legacy path the boolean maps straight across`() {
        assertEquals(
            Settings.VideoFitMode.FILL,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = true, legacyForcedScale = false, legacyViewMode = texture)
        )
        assertEquals(
            Settings.VideoFitMode.CONTAIN,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = false, legacyForcedScale = false, legacyViewMode = texture)
        )
    }

    // The legacy forcedScale/SurfaceView path read the boolean inverted: true meant bars, not
    // stretch. Migrating it straight across would flip what these users see.
    @Test
    fun `on the legacy forcedScale SurfaceView path the boolean is inverted`() {
        assertEquals(
            Settings.VideoFitMode.CONTAIN,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = true, legacyForcedScale = true, legacyViewMode = surface)
        )
        assertEquals(
            Settings.VideoFitMode.FILL,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = false, legacyForcedScale = true, legacyViewMode = surface)
        )
    }

    // The old getter read an unwritten boolean as true, and on this path true meant bars.
    @Test
    fun `a forced scale user who never touched the toggle keeps the bars`() {
        assertEquals(
            Settings.VideoFitMode.CONTAIN,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = null, legacyForcedScale = true, legacyViewMode = surface)
        )
    }

    @Test
    fun `forcedScale off SurfaceView is inactive so the boolean is not inverted`() {
        assertEquals(
            Settings.VideoFitMode.FILL,
            VideoFitPolicy.resolve(storedFitMode = null, legacyStretch = true, legacyForcedScale = true, legacyViewMode = texture)
        )
    }
}
