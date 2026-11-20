package com.lenid.zoh.utils

import org.junit.Assert.*
import org.junit.Test

class InstallPermissionRouteTest {
    @Test fun supportedDedicatedPageOpensOnce() {
        val seen = mutableListOf<InstallPermissionRoute.Page>()
        assertEquals(InstallPermissionRoute.Page.DEDICATED, InstallPermissionRoute.open { seen.add(it); true })
        assertEquals(listOf(InstallPermissionRoute.Page.DEDICATED), seen)
    }
    @Test fun missingDedicatedPageFallsBackAndBothMissingReturnNull() {
        val seen = mutableListOf<InstallPermissionRoute.Page>()
        assertEquals(InstallPermissionRoute.Page.APP_DETAILS, InstallPermissionRoute.open {
            seen.add(it); it == InstallPermissionRoute.Page.APP_DETAILS
        })
        assertEquals(InstallPermissionRoute.Page.values().toList(), seen)
        assertNull(InstallPermissionRoute.open { false })
    }
}
