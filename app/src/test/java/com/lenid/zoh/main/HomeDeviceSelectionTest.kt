package com.lenid.zoh.main

import org.junit.Assert.*
import org.junit.Test

class HomeDeviceSelectionTest {
    private val usb = HomeDevice("/usb/1", "Phone", HomeDevice.Kind.AA_USB)
    private val wifi = HomeDevice("AA:BB", "Phone", HomeDevice.Kind.AA_WIRELESS)

    @Test fun refreshDoesNotSelectFirstDeviceOrConnectIt() {
        assertNull(HomeDeviceSelection.retain(null, listOf(usb, wifi)))
    }
    @Test fun reorderKeepsExactTransportEvenWhenNamesMatch() {
        assertEquals(wifi.key, HomeDeviceSelection.retain(wifi.key, listOf(wifi, usb)))
    }
    @Test fun disconnectedDeviceClearsSelectionInsteadOfSelectingAnotherPhone() {
        assertNull(HomeDeviceSelection.retain(usb.key, listOf(wifi)))
    }
    @Test fun usbReenumerationRequiresNewSelectionInsteadOfUsingStalePath() {
        assertNull(HomeDeviceSelection.retain(usb.key, listOf(usb.copy(key = "/usb/2"))))
    }
    @Test fun activeConnectionAndCountdownKeepExistingControls() {
        listOf(HomePrimaryPolicy.Status.CONNECTING, HomePrimaryPolicy.Status.ACTIVE_SESSION,
            HomePrimaryPolicy.Status.RECONNECTING).forEach {
            assertFalse(HomeDeviceSelection.showsInventory(it))
        }
    }
    @Test fun passiveListeningDoesNotHideDeviceSelection() {
        listOf(HomePrimaryPolicy.Status.READY, HomePrimaryPolicy.Status.WAITING,
            HomePrimaryPolicy.Status.PHONE_DETECTED, HomePrimaryPolicy.Status.SEARCHING).forEach {
            assertTrue(HomeDeviceSelection.showsInventory(it))
        }
    }
}
