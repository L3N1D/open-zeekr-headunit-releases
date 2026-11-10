package com.lenid.zoh.decoder.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which components get Qualcomm's own low-latency key: only Qualcomm's. */
class LowLatencyFormatTest {

    @Test
    fun `Codec2 and OMX Qualcomm decoders are Qualcomm`() {
        assertTrue(LowLatencyFormat.isQualcomm("c2.qti.avc.decoder"))
        assertTrue(LowLatencyFormat.isQualcomm("OMX.qcom.video.decoder.avc"))
    }

    @Test
    fun `other vendors and software decoders are not`() {
        assertFalse(LowLatencyFormat.isQualcomm("c2.android.avc.decoder"))
        assertFalse(LowLatencyFormat.isQualcomm("OMX.MTK.VIDEO.DECODER.AVC"))
        assertFalse(LowLatencyFormat.isQualcomm("c2.goldfish.h264.decoder"))
    }
}
