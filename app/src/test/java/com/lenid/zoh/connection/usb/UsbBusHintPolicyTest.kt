package com.lenid.zoh.connection.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbBusHintPolicyTest {

    @Test
    fun `an empty bus is the empty-bus hint whatever else was seen`() {
        assertEquals(UsbBusHintPolicy.Hint.EMPTY_BUS, UsbBusHintPolicy.hint(0, 0, 4, featureDeclared = true))
    }

    @Test
    fun `a usable device needs no hint`() {
        assertNull(UsbBusHintPolicy.hint(1, 1, 3, featureDeclared = true))
    }

    @Test
    fun `one refused device is not a cycling adapter`() {
        assertEquals(UsbBusHintPolicy.Hint.NONE_USABLE, UsbBusHintPolicy.hint(1, 0, 1, featureDeclared = true))
    }

    @Test
    fun `enough composition changes inside the window is a cycling adapter`() {
        assertEquals(
            UsbBusHintPolicy.Hint.CYCLING_ADAPTER,
            UsbBusHintPolicy.hint(1, 0, UsbBusHintPolicy.CYCLING_CHANGES, featureDeclared = true)
        )
    }

    @Test
    fun `one plug-in is two changes at most and is not a cycling adapter`() {
        assertEquals(UsbBusHintPolicy.Hint.NONE_USABLE, UsbBusHintPolicy.hint(1, 0, 2, featureDeclared = true))
    }

    @Test
    fun `an empty bus on a unit that never declared USB host is a permanent verdict`() {
        assertEquals(
            UsbBusHintPolicy.Hint.NO_HOST_SUPPORT,
            UsbBusHintPolicy.hint(0, 0, 0, featureDeclared = false)
        )
    }

    @Test
    fun `a ROM that omits the feature but hosts devices anyway is never told it cannot`() {
        // Sloppy ROMs enumerate without declaring. Claiming NO_HOST_SUPPORT here would be a lie the
        // device itself disproves, so the ordinary refusal hints have to win.
        assertEquals(
            UsbBusHintPolicy.Hint.NONE_USABLE,
            UsbBusHintPolicy.hint(1, 0, 1, featureDeclared = false)
        )
        assertEquals(
            UsbBusHintPolicy.Hint.CYCLING_ADAPTER,
            UsbBusHintPolicy.hint(1, 0, UsbBusHintPolicy.CYCLING_CHANGES, featureDeclared = false)
        )
        assertNull(UsbBusHintPolicy.hint(1, 1, 0, featureDeclared = false))
    }

    @Test
    fun `a declared feature keeps the three-way empty-bus wording`() {
        assertEquals(
            UsbBusHintPolicy.Hint.EMPTY_BUS,
            UsbBusHintPolicy.hint(0, 0, 0, featureDeclared = true)
        )
    }

    @Test
    fun `the same identity twice is one identity`() {
        val now = 100_000L
        val seen = listOf(now - 30_000 to "0525:A4A7", now - 10_000 to "0525:A4A7")
        assertEquals(listOf("0525:A4A7"), UsbBusHintPolicy.identitiesInWindow(seen, now))
    }

    @Test
    fun `the reporter's adapter reads as two identities, newest first`() {
        val now = 300_000L
        val seen = listOf(
            now - 90_000 to "05AC:12A8",  // outside the window
            now - 40_000 to "0525:A4A7",
            now - 10_000 to "05AC:12A8",
        )
        assertEquals(listOf("05AC:12A8", "0525:A4A7"), UsbBusHintPolicy.identitiesInWindow(seen, now))
    }

    @Test
    fun `an identity exactly at the window edge is out`() {
        val now = 100_000L
        val seen = listOf(now - UsbBusHintPolicy.IDENTITY_WINDOW_MS to "0525:A4A7")
        assertEquals(emptyList<String>(), UsbBusHintPolicy.identitiesInWindow(seen, now))
    }

    @Test
    fun `a bus whose devices never change never moves the counter`() {
        // The reporter's unit: a USB hub and a built-in LTE modem, both permanent, neither usable.
        val now = 300_000L
        val bus = setOf("0424:4940", "19D2:1405")
        val scans = listOf(now - 40_000 to bus, now - 20_000 to bus, now - 1_000 to bus)
        assertEquals(0, UsbBusHintPolicy.compositionChanges(scans, now))
        assertEquals(
            UsbBusHintPolicy.Hint.NONE_USABLE,
            UsbBusHintPolicy.hint(2, 0, UsbBusHintPolicy.compositionChanges(scans, now), featureDeclared = true)
        )
    }

    @Test
    fun `a phone switching into accessory mode is not a cycling adapter`() {
        val now = 300_000L
        val fixed = setOf("0424:4940", "19D2:1405")
        val scans = listOf(
            now - 5_000 to fixed + "2717:FF08",   // attached in its own identity
            now - 4_000 to fixed,                 // gone while it switches
            now - 3_000 to fixed + "18D1:2D01",   // back as the accessory
        )
        assertEquals(2, UsbBusHintPolicy.compositionChanges(scans, now))
        assertEquals(
            UsbBusHintPolicy.Hint.NONE_USABLE,
            UsbBusHintPolicy.hint(3, 0, UsbBusHintPolicy.compositionChanges(scans, now), featureDeclared = true)
        )
    }

    @Test
    fun `a dongle cycling behind a hub still reaches the hint`() {
        val now = 300_000L
        val hub = setOf("0424:4940")
        val scans = listOf(
            now - 40_000 to hub + "0525:A4A7",
            now - 30_000 to hub,
            now - 20_000 to hub + "05AC:12A8",
            now - 10_000 to hub,
        )
        assertEquals(3, UsbBusHintPolicy.compositionChanges(scans, now))
        assertEquals(
            UsbBusHintPolicy.Hint.CYCLING_ADAPTER,
            UsbBusHintPolicy.hint(1, 0, UsbBusHintPolicy.compositionChanges(scans, now), featureDeclared = true)
        )
    }

    @Test
    fun `a change whose earlier side fell out of the window is not counted`() {
        val now = 300_000L
        val scans = listOf(
            now - 90_000 to setOf("0525:A4A7"),   // outside
            now - 10_000 to setOf("05AC:12A8"),
        )
        assertEquals(0, UsbBusHintPolicy.compositionChanges(scans, now))
    }
}
