package com.lenid.zoh.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashJournalMaskTest {

    @Test
    fun coordinatesAndAddressesAreMasked() {
        assertEquals("at ***,*** from **:**:**:**:**:**",
            CrashJournal.mask("at 55.156961,61.419419 from A4:C3:F0:12:3B:9E"))
    }

    @Test
    fun shortNumbersStay() {
        assertEquals("fps 23.5, 1920x480", CrashJournal.mask("fps 23.5, 1920x480"))
    }

    @Test
    fun occurrencesDifferingOnlyInNumbersShareATemplate() {
        assertEquals(CrashJournal.template("frame 1203 dropped after 45 ms"),
            CrashJournal.template("frame 88 dropped after 3 ms"))
    }
}
