package com.lenid.zoh.main.settings

import com.lenid.zoh.R

/** Maintenance rows share presentation, while their existing import/export callbacks stay intact. */
internal object MaintenanceSettings {
    fun entries(exportDescription: String, importDescription: String, export: () -> Unit, restore: () -> Unit, tools: () -> Unit): List<SettingItem> = listOf(
        SettingItem.CategoryHeader("backup", R.string.category_backup),
        SettingItem.SettingEntry("exportSettings", R.string.export_settings, exportDescription, onClick = { export() }),
        SettingItem.SettingEntry("importSettings", R.string.import_settings, importDescription, onClick = { restore() }),
        SettingItem.SettingEntry("supportTools", R.string.support_tools, "", onClick = { tools() })
    )
}
