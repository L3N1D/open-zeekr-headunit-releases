package com.lenid.zoh.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Test

class StationStandDownModeTest {

    @Test
    fun `every mode survives the round trip through its setting value`() {
        for (mode in StationStandDownMode.values()) {
            assertEquals(mode, StationStandDownMode.fromSetting(mode.ordinal))
        }
    }

    @Test
    fun `auto is the default and the answer to anything unrecognised`() {
        assertEquals(0, StationStandDownMode.AUTO.ordinal)
        assertEquals(StationStandDownMode.AUTO, StationStandDownMode.fromSetting(-1))
        assertEquals(StationStandDownMode.AUTO, StationStandDownMode.fromSetting(7))
    }
}
