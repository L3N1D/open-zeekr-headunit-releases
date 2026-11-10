package com.lenid.zoh.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Test

class WppChannelListPolicyTest {

    @Test
    fun `an unreadable frequency names no channel`() {
        assertEquals(emptyList<Int>(), WppChannelListPolicy.forGroup(0))
    }

    @Test
    fun `a 5 GHz group names its own frequency`() {
        assertEquals(listOf(5180), WppChannelListPolicy.forGroup(5180))
    }

    @Test
    fun `a 2_4 GHz group names its own frequency`() {
        assertEquals(listOf(2437), WppChannelListPolicy.forGroup(2437))
    }

    @Test
    fun `a value that describes no real channel is never sent`() {
        assertEquals(emptyList<Int>(), WppChannelListPolicy.forGroup(-1))
        assertEquals(emptyList<Int>(), WppChannelListPolicy.forGroup(2399))
        assertEquals(emptyList<Int>(), WppChannelListPolicy.forGroup(5000))
    }
}
