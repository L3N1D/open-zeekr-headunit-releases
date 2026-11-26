package com.lenid.zoh.main

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LauncherEntryRequestTest {
    private val intent = mock<Intent>()

    @Test fun drawerIconRequestsFullscreen() {
        whenever(intent.action).thenReturn(Intent.ACTION_MAIN)
        whenever(intent.hasCategory(Intent.CATEGORY_LAUNCHER)).thenReturn(true)
        assertTrue(LauncherEntryRequest.isDrawerIcon(intent))
    }

    @Test fun launcherCardMarkersOverrideIconEvenIfLauncherActionIsPresent() {
        whenever(intent.action).thenReturn(Intent.ACTION_MAIN)
        whenever(intent.hasCategory(Intent.CATEGORY_LAUNCHER)).thenReturn(true)
        for (key in listOf("launcher_view_port_delta_x", "map_view_left_margin", "launcher_resume_to_map")) {
            whenever(intent.hasExtra(key)).thenReturn(true)
            assertTrue(LauncherEntryRequest.isMapCard(intent))
            assertFalse(LauncherEntryRequest.isDrawerIcon(intent))
            whenever(intent.hasExtra(key)).thenReturn(false)
        }
    }

    @Test fun openingComponentByNameDoesNotIdentifyIconTap() {
        assertFalse(LauncherEntryRequest.isDrawerIcon(intent))
    }
}
