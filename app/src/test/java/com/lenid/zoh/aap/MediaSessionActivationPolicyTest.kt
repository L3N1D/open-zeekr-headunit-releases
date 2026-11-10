package com.lenid.zoh.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionActivationPolicyTest {

    @Test
    fun `a loopback session does not activate the media session`() {
        assertFalse(MediaSessionActivationPolicy.shouldActivate(isLoopbackSession = true))
    }

    @Test
    fun `a remote session activates the media session`() {
        assertTrue(MediaSessionActivationPolicy.shouldActivate(isLoopbackSession = false))
    }

    @Test
    fun `a loopback session never syncs metadata, whatever the preference`() {
        assertFalse(MediaSessionActivationPolicy.shouldSyncAaMetadata(isLoopbackSession = true, userPreference = true))
        assertFalse(MediaSessionActivationPolicy.shouldSyncAaMetadata(isLoopbackSession = true, userPreference = false))
    }

    @Test
    fun `a remote session follows the preference`() {
        assertTrue(MediaSessionActivationPolicy.shouldSyncAaMetadata(isLoopbackSession = false, userPreference = true))
        assertFalse(MediaSessionActivationPolicy.shouldSyncAaMetadata(isLoopbackSession = false, userPreference = false))
    }
}
