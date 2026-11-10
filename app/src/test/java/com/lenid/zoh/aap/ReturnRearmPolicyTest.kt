package com.lenid.zoh.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnRearmPolicyTest {
    @Test fun keptSurfaceWithStoppedDecoderRearms() {
        assertTrue(ReturnRearmPolicy.shouldRearm(true, true, 1_000L, 5_000L))
    }

    @Test fun surfaceCallbackThatAlreadyArmedDoesNotRearmTwice() {
        assertFalse(ReturnRearmPolicy.shouldRearm(true, true, 5_100L, 5_000L))
    }

    @Test fun stopThatStoppedNothingDoesNotRearm() {
        assertFalse(ReturnRearmPolicy.shouldRearm(false, true, 1_000L, 5_000L))
    }

    @Test fun noSurfaceDoesNotRearm() {
        assertFalse(ReturnRearmPolicy.shouldRearm(true, false, 1_000L, 5_000L))
    }
}
