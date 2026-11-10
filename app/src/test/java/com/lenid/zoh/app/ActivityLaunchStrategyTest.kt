package com.lenid.zoh.app

import com.lenid.zoh.app.ActivityLaunchPolicy.LaunchStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityLaunchStrategyTest {

    @Test
    fun `direct launch on Android 9 and below regardless of overlay permission`() {
        assertEquals(LaunchStrategy.DIRECT, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 28, canDrawOverlays = false))
        assertEquals(LaunchStrategy.DIRECT, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 28, canDrawOverlays = true))
    }

    @Test
    fun `overlay trampoline on Android 10 plus when permission granted`() {
        assertEquals(LaunchStrategy.OVERLAY, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 29, canDrawOverlays = true))
    }

    @Test
    fun `notification fallback on Android 10 plus without overlay permission`() {
        assertEquals(LaunchStrategy.NOTIFICATION, ActivityLaunchPolicy.chooseLaunchStrategy(apiLevel = 29, canDrawOverlays = false))
    }

    /**
     * The restriction the other two arms work around does not apply to an app that already has a
     * visible activity, and falling back anyway left a session waiting on a notification tap.
     */
    @Test
    fun `a foreground app starts the activity directly whatever the overlay permission says`() {
        assertEquals(
            LaunchStrategy.DIRECT,
            ActivityLaunchPolicy.chooseLaunchStrategy(34, canDrawOverlays = false, appIsForeground = true)
        )
        assertEquals(
            LaunchStrategy.DIRECT,
            ActivityLaunchPolicy.chooseLaunchStrategy(34, canDrawOverlays = true, appIsForeground = true)
        )
    }

    @Test
    fun `a background app keeps the old table`() {
        assertEquals(
            LaunchStrategy.OVERLAY,
            ActivityLaunchPolicy.chooseLaunchStrategy(34, canDrawOverlays = true, appIsForeground = false)
        )
        assertEquals(
            LaunchStrategy.NOTIFICATION,
            ActivityLaunchPolicy.chooseLaunchStrategy(34, canDrawOverlays = false, appIsForeground = false)
        )
    }
}
