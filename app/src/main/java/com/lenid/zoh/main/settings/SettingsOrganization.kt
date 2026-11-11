package com.lenid.zoh.main.settings

import com.lenid.zoh.R

/** Presentation only: keep the original row instances, callbacks and preference keys. */
internal object SettingsOrganization {
    fun canonicalCategory(id: String): String = when (id) {
        "video" -> "graphic"
        "UI" -> "theming"
        "resetSettingsCategory" -> "backup"
        "navigation" -> "moreFeatures"
        else -> id
    }

    fun organize(items: List<SettingItem>): List<SettingItem> {
        val headers = linkedMapOf<String, SettingItem.CategoryHeader>()
        val groups = linkedMapOf<String, MutableList<SettingItem>>()
        val trailing = mutableListOf<SettingItem>()
        var category = "general"
        for (item in items) {
            if (item is SettingItem.CategoryHeader) {
                category = canonicalCategory(item.stableId)
                headers.putIfAbsent(category, item.copy(stableId = category))
                continue
            }
            if (item.stableId == "bottomSaveButton") { trailing.add(item); continue }
            val target = when {
                item.stableId in setOf("connectionMode", "useLibusb") -> "connection"
                item.stableId in setOf("vehicleInfoSettings", "hudMirroring") -> "moreFeatures"
                item.stableId == "enableFloatingButton" || item.stableId.startsWith("floatingButton") -> "theming"
                else -> category
            }
            groups.getOrPut(target) { mutableListOf() }.add(item)
        }
        val titles = mapOf(
            "connection" to R.string.settings_connection_section,
            "graphic" to R.string.settings_picture_section,
            "moreFeatures" to R.string.settings_vehicle_section,
            "theming" to R.string.settings_appearance_section,
            "backup" to R.string.settings_transfer_section,
            "debug" to R.string.settings_diagnostics_section,
            "info" to R.string.settings_updates_section
        )
        val order = listOf("general", "connection", "wirelessConnection", "dongle", "automation",
            "moreFeatures", "graphic", "audio", "input", "theming", "backup", "debug", "info")
        return buildList {
            for (id in (order + groups.keys).distinct()) {
                val rows = groups[id] ?: continue
                add(SettingItem.CategoryHeader(id, titles[id] ?: headers.getValue(id).titleResId))
                addAll(rows)
            }
            addAll(trailing)
        }
    }
}
