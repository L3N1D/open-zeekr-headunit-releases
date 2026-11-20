package com.lenid.zoh.utils

import org.junit.Assert.*
import org.junit.Test

class WakePicturePolicyTest {
    @Test fun onlyLiveInputWithStalledRenderedPictureRequestsRecovery() {
        assertTrue(WakePicturePolicy.shouldRequest(20_000, 19_500, 1_000))
        assertFalse(WakePicturePolicy.shouldRequest(20_000, 19_500, 19_000))
        assertFalse(WakePicturePolicy.shouldRequest(20_000, 1_000, 1_000))
    }
    @Test fun noRecoveryBeforeFirstFrameOrAfterTimestampReset() {
        assertFalse(WakePicturePolicy.shouldRequest(20_000, 19_500, 0))
        assertFalse(WakePicturePolicy.shouldRequest(100, 19_500, 1_000))
        assertFalse(WakePicturePolicy.shouldRequest(20_000, 0, 1_000))
    }
}
