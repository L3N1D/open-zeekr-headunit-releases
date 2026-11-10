package com.lenid.zoh.connection.wifi.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRadioSwitchPolicyTest {

    @Test
    fun `an app may still switch the radio on below api 29`() {
        assertTrue(WifiRadioSwitchPolicy.appCanEnable(28))
        assertFalse(WifiRadioSwitchPolicy.appCanEnable(29))
        assertFalse(WifiRadioSwitchPolicy.appCanEnable(33))
    }

    @Test
    fun `a radio that is on is never blocked off`() {
        assertFalse(WifiRadioSwitchPolicy.isBlockedOff(wifiEnabled = true, sdkInt = 33))
        assertFalse(WifiRadioSwitchPolicy.isBlockedOff(wifiEnabled = true, sdkInt = 28))
    }

    @Test
    fun `a radio that is off below api 29 is not blocked, because the app can ask`() {
        assertFalse(WifiRadioSwitchPolicy.isBlockedOff(wifiEnabled = false, sdkInt = 28))
    }

    @Test
    fun `a radio that is off from api 29 is blocked`() {
        assertTrue(WifiRadioSwitchPolicy.isBlockedOff(wifiEnabled = false, sdkInt = 29))
        assertTrue(WifiRadioSwitchPolicy.isBlockedOff(wifiEnabled = false, sdkInt = 33))
    }
}
