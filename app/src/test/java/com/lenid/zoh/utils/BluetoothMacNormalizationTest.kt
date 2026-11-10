package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The head unit's own Bluetooth address is how the phone is told where to connect hands-free, and
 * a separator-only check threw away the one source the ZJ units actually publish.
 */
class BluetoothMacNormalizationTest {

    @Test
    fun `an unseparated vendor property is an address`() {
        // persist.zj.BTmac reads exactly this on #978's GT7H-CAR and GT6-CAR.
        assertEquals("00:87:61:BF:67:06", BluetoothHelper.normalizeMacAddress("008761BF6706"))
        assertEquals("00:87:61:81:29:02", BluetoothHelper.normalizeMacAddress("008761812902"))
    }

    @Test
    fun `the separated forms still work and come back canonical`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothHelper.normalizeMacAddress("aa:bb:cc:dd:ee:ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothHelper.normalizeMacAddress("AA-BB-CC-DD-EE-FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothHelper.normalizeMacAddress("  AA:BB:CC:DD:EE:FF "))
    }

    @Test
    fun `the masked placeholders are refused in both spellings`() {
        assertNull(BluetoothHelper.normalizeMacAddress("02:00:00:00:00:00"))
        assertNull(BluetoothHelper.normalizeMacAddress("020000000000"))
        assertNull(BluetoothHelper.normalizeMacAddress("00:00:00:00:00:00"))
        assertNull(BluetoothHelper.normalizeMacAddress("000000000000"))
    }

    @Test
    fun `anything that is not an address stays refused`() {
        assertNull(BluetoothHelper.normalizeMacAddress(null))
        assertNull(BluetoothHelper.normalizeMacAddress(""))
        assertNull(BluetoothHelper.normalizeMacAddress("   "))
        assertNull(BluetoothHelper.normalizeMacAddress("008761BF670"))
        assertNull(BluetoothHelper.normalizeMacAddress("008761BF67066"))
        assertNull(BluetoothHelper.normalizeMacAddress("00:87:61:BF:67"))
        assertNull(BluetoothHelper.normalizeMacAddress("GG8761BF6706"))
    }
}
