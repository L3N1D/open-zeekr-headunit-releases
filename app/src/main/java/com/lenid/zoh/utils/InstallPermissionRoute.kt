package com.lenid.zoh.utils

internal object InstallPermissionRoute {
    enum class Page { DEDICATED, APP_DETAILS }
    fun open(launch: (Page) -> Boolean): Page? = Page.values().firstOrNull(launch)
}
