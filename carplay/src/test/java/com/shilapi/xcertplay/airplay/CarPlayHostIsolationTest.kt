package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayHostIsolationTest {
    @Test fun defaultHostAdvertisesNoVehicleControlsAndRefusesWrites() {
        val listener = object : AirPlaySessionListener {}
        assertEquals(CarPlayVehicleProfile.NONE, listener.vehicleProfile())
        assertFalse(listener.onVehicleWrite("driverTargetTemperature", 25.0))
        assertFalse(listener.onVehicleWrite("hvacOn", true))
    }
    @Test fun enablingUltraPreservesVideoCanvasAndWidgetViewAreas() {
        val areas = listOf(AirPlayViewArea(1275, 876), AirPlayViewArea(1796, 876))
        val ordinary = AirPlayConfig(deviceName="test", deviceId="02:00:00:00:00:02",
            btMac="02:00:00:00:00:02", sourceVersion="950.7.1",
            main=AirPlayDisplayConfig(1920,1080,viewAreas=areas))
        val ultra = ordinary.copy(ultraFeatures=CarPlayUltra.DEFAULT_FEATURES)
        val before=AirPlayInfoPlist.build(ordinary)
        val after=AirPlayInfoPlist.build(ultra)
        assertEquals(before["displays"],after["displays"])
        assertEquals(areas,ultra.main.viewAreas)
        assertNull(ultra.cluster)
        assertTrue(ordinary.ultraFeatures.isEmpty())
    }
}
