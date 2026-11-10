package com.lenid.zoh.connection.wifi.modes.nativeaa

import com.lenid.zoh.aap.protocol.proto.Wireless
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WppChannelTypePolicyTest {

    @Test
    fun `a five gigahertz radio is announced as dual band`() {
        assertEquals(
            Wireless.WifiChannelType.CHANNELS_DUAL_BAND,
            WppChannelTypePolicy.forHeadUnit(true)
        )
    }

    @Test
    fun `a two point four only radio is announced as such`() {
        assertEquals(
            Wireless.WifiChannelType.CHANNELS_24GHZ_ONLY,
            WppChannelTypePolicy.forHeadUnit(false)
        )
    }

    @Test
    fun `an unreadable radio announces nothing rather than guessing`() {
        assertNull(WppChannelTypePolicy.forHeadUnit(null))
    }

    @Test
    fun `we never claim five gigahertz only`() {
        val claimed = listOf(true, false, null).map { WppChannelTypePolicy.forHeadUnit(it) }
        assert(claimed.none { it == Wireless.WifiChannelType.CHANNELS_5GHZ_ONLY })
    }
}
