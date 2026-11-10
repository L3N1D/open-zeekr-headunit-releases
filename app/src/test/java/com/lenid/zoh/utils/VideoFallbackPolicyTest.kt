package com.lenid.zoh.utils

import com.lenid.zoh.aap.protocol.proto.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias R = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType

class VideoFallbackPolicyTest {

    @Test
    fun `landscape primaries above 720p fall back to 1280x720`() {
        assertEquals(R._1280x720, VideoFallbackPolicy.fallbackFor(R._1920x1080))
        assertEquals(R._1280x720, VideoFallbackPolicy.fallbackFor(R._2560x1440))
        assertEquals(R._1280x720, VideoFallbackPolicy.fallbackFor(R._3840x2160))
    }

    @Test
    fun `portrait primaries above 720p fall back to 720x1280`() {
        assertEquals(R._720x1280, VideoFallbackPolicy.fallbackFor(R._1080x1920))
        assertEquals(R._720x1280, VideoFallbackPolicy.fallbackFor(R._1440x2560))
        assertEquals(R._720x1280, VideoFallbackPolicy.fallbackFor(R._2160x3840))
    }

    @Test
    fun `sizes the phone never refuses offer no fallback`() {
        assertNull(VideoFallbackPolicy.fallbackFor(R._800x480))
        assertNull(VideoFallbackPolicy.fallbackFor(R._1280x720))
        assertNull(VideoFallbackPolicy.fallbackFor(R._720x1280))
    }

    @Test
    fun `margins scale in proportion so the visible picture keeps its shape`() {
        // 1920x1080 with 0 and 120 rows hidden: the same share of 1280x720 is 0 and 80.
        assertEquals(
            VideoFallbackPolicy.Margins(0, 80),
            VideoFallbackPolicy.scaledMargins(1920, 1080, 0, 120, R._1280x720)
        )
        // 2560x1440 with 400 columns hidden: 400 * 1280 / 2560 = 200.
        assertEquals(
            VideoFallbackPolicy.Margins(200, 0),
            VideoFallbackPolicy.scaledMargins(2560, 1440, 400, 0, R._1280x720)
        )
    }

    @Test
    fun `scaled margins round to the nearest pixel`() {
        // 101 * 2 / 3 = 67.33 and 100 * 2 / 3 = 66.67.
        assertEquals(
            VideoFallbackPolicy.Margins(67, 67),
            VideoFallbackPolicy.scaledMargins(1920, 1080, 101, 100, R._1280x720)
        )
    }

    @Test
    fun `the visible share is the same at both sizes`() {
        val primaryW = 1920
        val primaryH = 1080
        val margins = VideoFallbackPolicy.scaledMargins(primaryW, primaryH, 240, 0, R._1280x720)
        assertEquals((primaryW - 240).toDouble() / primaryW, (1280 - margins.width).toDouble() / 1280, 0.001)
    }

    @Test
    fun `portrait margins scale against the portrait size`() {
        assertEquals(
            VideoFallbackPolicy.Margins(60, 0),
            VideoFallbackPolicy.scaledMargins(1080, 1920, 90, 0, R._720x1280)
        )
    }

    @Test
    fun `the setup reply lists the fallback only when it was offered`() {
        assertEquals(listOf(0, 1), VideoFallbackPolicy.configurationIndices(true))
        assertEquals(listOf(0), VideoFallbackPolicy.configurationIndices(false))
    }

    @Test
    fun `only index 1 of an offered fallback is adopted`() {
        assertTrue(VideoFallbackPolicy.adopts(1, offered = true))
        assertFalse(VideoFallbackPolicy.adopts(0, offered = true))
        assertFalse(VideoFallbackPolicy.adopts(1, offered = false))
        assertFalse(VideoFallbackPolicy.adopts(2, offered = true))
    }
}
