package com.lenid.zoh.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartOverlayPolicyTest {

    /** It ends a session rather than starting one, so no window is ever raised for it. */
    @Test
    fun `auto-disconnect alone needs no overlay`() {
        assertFalse(
            AutoStartOverlayPolicy.launches(
                onBoot = false, onScreenOn = false, onUsb = false, onWifi = false,
                btTriggerCount = 0
            )
        )
    }

    @Test
    fun `any launching trigger needs the overlay`() {
        assertTrue(
            AutoStartOverlayPolicy.launches(
                onBoot = false, onScreenOn = false, onUsb = false, onWifi = false,
                btTriggerCount = 1
            )
        )
        assertTrue(
            AutoStartOverlayPolicy.launches(
                onBoot = true, onScreenOn = false, onUsb = false, onWifi = false,
                btTriggerCount = 0
            )
        )
    }

    @Test
    fun `a granted permission shows no notice`() {
        assertFalse(AutoStartOverlayPolicy.showsNotice(overlayGranted = true, launches = true))
    }

    @Test
    fun `a missing permission with nothing configured shows no notice`() {
        assertFalse(AutoStartOverlayPolicy.showsNotice(overlayGranted = false, launches = false))
    }

    @Test
    fun `a missing permission with a trigger configured shows the notice`() {
        assertTrue(AutoStartOverlayPolicy.showsNotice(overlayGranted = false, launches = true))
    }
}
