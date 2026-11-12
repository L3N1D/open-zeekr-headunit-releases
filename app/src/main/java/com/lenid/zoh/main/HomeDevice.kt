package com.lenid.zoh.main

/** UI inventory, never proof that a paired phone is present or supports projection. */
internal data class HomeDevice(val key: String, val name: String, val kind: Kind) {
    enum class Kind { AA_USB, AA_WIRELESS, CARPLAY_USB, CARPLAY_WIRELESS, DONGLE }
}

internal object HomeDeviceSelection {
    fun retain(selected: String?, devices: List<HomeDevice>): String? =
        selected?.takeIf { key -> devices.any { it.key == key } }

    fun showsInventory(status: HomePrimaryPolicy.Status): Boolean = status !in setOf(
        HomePrimaryPolicy.Status.ACTIVE_SESSION, HomePrimaryPolicy.Status.CONNECTING,
        HomePrimaryPolicy.Status.RECONNECTING)
}
