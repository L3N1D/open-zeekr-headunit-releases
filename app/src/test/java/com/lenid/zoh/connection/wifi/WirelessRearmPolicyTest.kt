package com.lenid.zoh.connection.wifi

import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessRearmPolicyTest {

    private fun config(
        mode: WifiLauncherMode = WifiLauncherMode.NATIVE,
        native: NativeStrategy = NativeStrategy.WIFI_DIRECT,
        bluetoothService: String = "bluetooth_manager",
        wirelessSelected: Boolean = true,
        band: Int = 0,
        channel: Int = 0,
        autoEnableHotspot: Boolean = false,
        insecureRfcomm: Boolean = false,
    ) = WirelessRearmPolicy.Config(
        wifiConnectionMode = mode,
        nativeApStrategy = native,
        bluetoothManagerServiceName = bluetoothService,
        wirelessSelected = wirelessSelected,
        wifiDirectBand = band,
        fiveGhzChannel = channel,
        autoEnableHotspot = autoEnableHotspot,
        insecureAaRfcommListener = insecureRfcomm,
    )

    @Test
    fun `an unchanged configuration re-arms nothing`() {
        assertFalse(WirelessRearmPolicy.requiresRearm(config(), config()))
    }



    /** The one that was missing: a saved hotspot left the launcher hosting a P2P group. */

    @Test
    fun `the Bluetooth service name re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(bluetoothService = "syu_bt"))
        )
    }

    /** Unchecking WiFi in Connection mode has to reach the running stack, not wait for a restart. */
    @Test
    fun `dropping wireless from the chosen connection modes re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(wirelessSelected = false))
        )
    }

    /**
     * The six below were applied only as a side effect of the settings screen closing, which used
     * to re-arm unconditionally. Saving them now has to ask for the re-arm itself.
     */
    @Test
    fun `the WiFi Direct band re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(band = 2)))
    }

    @Test
    fun `the 5 GHz channel re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(channel = 149)))
    }

    @Test
    fun `the hotspot auto-enable re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(autoEnableHotspot = true)))
    }

    @Test
    fun `the insecure RFCOMM listener re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(insecureRfcomm = true)))
    }
}
