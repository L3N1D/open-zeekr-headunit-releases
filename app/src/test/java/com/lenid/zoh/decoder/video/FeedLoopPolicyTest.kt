package com.lenid.zoh.decoder.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedLoopPolicyTest {
    @Test fun feedsWhenEverythingIsLive() {
        assertTrue(FeedLoopPolicy.shouldFeed(true, true, true, false))
    }

    @Test fun refusesWhenARestartIsPending() {
        assertFalse(FeedLoopPolicy.shouldFeed(true, true, true, true))
    }

    @Test fun refusesWhenStoppedSupersededOrCodecless() {
        assertFalse(FeedLoopPolicy.shouldFeed(false, true, true, false))
        assertFalse(FeedLoopPolicy.shouldFeed(true, false, true, false))
        assertFalse(FeedLoopPolicy.shouldFeed(true, true, false, false))
    }
}
