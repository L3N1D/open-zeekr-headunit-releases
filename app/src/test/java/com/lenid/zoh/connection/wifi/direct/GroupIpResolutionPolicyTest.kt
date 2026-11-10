package com.lenid.zoh.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupIpResolutionPolicyTest {

    @Test
    fun `group owner reads the interface once and does not retry`() {
        assertEquals(0, GroupIpResolutionPolicy.retriesAfterFirstRead(isGroupOwner = true))
    }

    @Test
    fun `a client still waits out its lease, unchanged`() {
        assertEquals(
            GroupIpResolutionPolicy.CLIENT_RETRIES,
            GroupIpResolutionPolicy.retriesAfterFirstRead(isGroupOwner = false)
        )
    }

    @Test
    fun `a readable address is used whoever we are`() {
        assertEquals("192.168.49.37", GroupIpResolutionPolicy.resolve("192.168.49.37", isGroupOwner = true))
        assertEquals("192.168.49.37", GroupIpResolutionPolicy.resolve("192.168.49.37", isGroupOwner = false))
    }

    @Test
    fun `an unreadable interface falls back to the fixed address only for the owner`() {
        assertEquals(
            GroupIpResolutionPolicy.GROUP_OWNER_IP,
            GroupIpResolutionPolicy.resolve(null, isGroupOwner = true)
        )
        assertNull(GroupIpResolutionPolicy.resolve(null, isGroupOwner = false))
    }
}
