package com.lenid.zoh.connection.wifi.direct

import com.lenid.zoh.connection.wifi.direct.NativeBringUpReentryPolicy.DUPLICATE_WINDOW_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape this exists for: two bring-ups 12ms apart, one group created and one BUSY, and the
 * BUSY removing the group the other had just made under a phone that was already on it.
 */
class NativeBringUpReentryPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `a second bring-up milliseconds after the first is a duplicate`() {
        assertTrue(NativeBringUpReentryPolicy.isDuplicate(now, lastStartedAtMs = now - 12))
    }

    @Test
    fun `the first bring-up of all is never a duplicate`() {
        assertFalse(NativeBringUpReentryPolicy.isDuplicate(now, lastStartedAtMs = 0L))
    }

    @Test
    fun `the window ends, so a mode can always be brought up again`() {
        assertTrue(NativeBringUpReentryPolicy.isDuplicate(now, now - DUPLICATE_WINDOW_MS + 1))
        assertFalse(NativeBringUpReentryPolicy.isDuplicate(now, now - DUPLICATE_WINDOW_MS))
    }

    @Test
    fun `the WiFi-enable retry re-enters on purpose and is left alone`() {
        // startNativeAaQuietHost() posts itself again 2s later while it waits for the radio.
        assertFalse(NativeBringUpReentryPolicy.isDuplicate(now, lastStartedAtMs = now - 2_000))
    }

    @Test
    fun `a clock that went backwards is not read as a duplicate`() {
        assertFalse(NativeBringUpReentryPolicy.isDuplicate(now, lastStartedAtMs = now + 5_000))
    }
}
