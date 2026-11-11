package com.lenid.zoh.main.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsOrganizationTest {
    private fun row(id: String) = SettingItem.ActionButton(id, 1) {}

    @Test fun regroupingPreservesEveryRowAndItsCallbackInstance() {
        val ids = listOf("connectionMode", "useLibusb", "vehicleInfoSettings", "ordinary")
        val rows = ids.map(::row)
        val save = row("bottomSaveButton")
        val input = listOf(SettingItem.CategoryHeader("general", 1)) + rows +
            listOf(SettingItem.CategoryHeader("video", 2), row("videoCodec"), save)
        val output = SettingsOrganization.organize(input)
        val before = input.filterNot { it is SettingItem.CategoryHeader }
        val after = output.filterNot { it is SettingItem.CategoryHeader }
        assertEquals(before.map { it.stableId }.toSet(), after.map { it.stableId }.toSet())
        assertEquals(before.size, after.size)
        before.forEach { original -> assertSame(original, after.single { it.stableId == original.stableId }) }
        assertSame(save, output.last())
    }

    @Test fun wirelessRowsKeepTheirCategoryForConnectionFiltering() {
        val row = row("staticBSSID")
        val output = SettingsOrganization.organize(listOf(SettingItem.CategoryHeader("wirelessConnection", 1), row))
        assertEquals("wirelessConnection", output.first().stableId)
        assertSame(row, output.last())
    }

    @Test fun mergedSectionsKeepRelativeOrderAndNoEmptySections() {
        val output = SettingsOrganization.organize(listOf(
            SettingItem.CategoryHeader("graphic", 1), row("resolution"), row("dpiPixelDensity"),
            SettingItem.CategoryHeader("video", 2), row("videoCodec"),
            SettingItem.CategoryHeader("audio", 3)))
        assertEquals(listOf("graphic", "resolution", "dpiPixelDensity", "videoCodec"), output.map { it.stableId })
    }

    @Test fun legacyShortcutsResolveToNewCategories() {
        assertEquals("graphic", SettingsOrganization.canonicalCategory("video"))
        assertEquals("theming", SettingsOrganization.canonicalCategory("UI"))
        assertEquals("backup", SettingsOrganization.canonicalCategory("resetSettingsCategory"))
        assertEquals("moreFeatures", SettingsOrganization.canonicalCategory("navigation"))
        assertEquals("dongle", SettingsOrganization.canonicalCategory("dongle"))
    }
}
