package com.lenid.zoh.decoder.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AacDecoderRecoveryPolicyTest {

    @Test
    fun `the first failure is always rebuilt`() {
        assertTrue(AacDecoderRecoveryPolicy.allowsRebuild(rebuildsSoFar = 0, sinceLastRebuildMs = 0))
    }

    @Test
    fun `a second failure waits out the spacing`() {
        assertFalse(AacDecoderRecoveryPolicy.allowsRebuild(1, AacDecoderRecoveryPolicy.MIN_SPACING_MS - 1))
        assertTrue(AacDecoderRecoveryPolicy.allowsRebuild(1, AacDecoderRecoveryPolicy.MIN_SPACING_MS))
    }

    @Test
    fun `the budget ends the churn`() {
        assertTrue(AacDecoderRecoveryPolicy.allowsRebuild(AacDecoderRecoveryPolicy.MAX_REBUILDS - 1, Long.MAX_VALUE))
        assertFalse(AacDecoderRecoveryPolicy.allowsRebuild(AacDecoderRecoveryPolicy.MAX_REBUILDS, Long.MAX_VALUE))
    }
}
