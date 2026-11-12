package com.lenid.zoh.carplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.lenid.zoh.R
import org.junit.Assert.*
import org.junit.Test

class CarPlayDiagnosticsTest {
    @Test fun reportNeverIncludesPhoneOrNetworkFieldsOrRawErrors() {
        CarPlayDiagnostics.begin(true, false)
        CarPlayDiagnostics.record(CarPlayStatus.HotspotReady("privateSSID", "5GHz", 36,
            "AA:BB:CC:DD:EE:FF", "192.168.8.4", "privateBackend"))
        CarPlayDiagnostics.record(CarPlayStatus.Failed("USBError: Pipe error privatePassword privatePhone"))
        val report = CarPlayDiagnostics.report()
        listOf("privateSSID", "AA:BB", "192.168", "privateBackend", "privatePassword", "privatePhone")
            .forEach { assertFalse(report, report.contains(it)) }
        assertTrue(report.contains("after=HotspotReady cause=USB_IO"))
    }
    @Test fun reportRemainsBoundedAndNewAttemptClearsOldStages() {
        CarPlayDiagnostics.begin(false, false)
        repeat(500) { CarPlayDiagnostics.record(CarPlayStatus.ConnectingControl) }
        assertTrue(CarPlayDiagnostics.report().lines().size <= 103)
        CarPlayDiagnostics.begin(true, true)
        assertFalse(CarPlayDiagnostics.report().contains("ConnectingControl"))
        assertTrue(CarPlayDiagnostics.report().contains("ultra=true"))
    }
    @Test fun usbFailureDoesNotTellUserToReplaceAuthentication() {
        assertEquals(R.string.carplay_error_usb,
            CarPlayDiagnostics.message(CarPlayStatus.Failed("USBError: [Errno 5] Input/Output Error")))
        assertEquals(R.string.carplay_error_timeout,
            CarPlayDiagnostics.message(CarPlayStatus.Failed("Timed out waiting for USBMUX")))
        assertEquals(R.string.carplay_stage_usb_missing,
            CarPlayDiagnostics.message(CarPlayStatus.IphoneNotListed(false)))
    }
}
