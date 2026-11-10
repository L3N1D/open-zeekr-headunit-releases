package com.lenid.zoh.connection.wifi.scan

import com.lenid.zoh.R
import com.lenid.zoh.connection.wifi.scan.WifiScanControl.State
import org.junit.Assert.*
import org.junit.Test

class ScanControlSetupTest {
    private fun actions(installed: Boolean = true, running: Boolean = false, allowed: Boolean = false,
                        legacy: Boolean = false,
                        busy: Boolean = false, state: State = State.OFF, supported: Boolean = true) =
        ScanControlSetup.actions(installed, running, allowed, legacy, busy, state, supported)




    @Test fun `manager action remains available when setup is needed even with the option off`() {
        assertEquals(R.string.wifi_scan_install, actions(installed = false).managerLabel)
        assertTrue(actions(installed = false).managerEnabled)
        assertEquals(R.string.wifi_scan_open_shizuku, actions().managerLabel)
        assertTrue(actions().managerEnabled)
        assertFalse(actions(busy = true).managerEnabled)
    }

    @Test fun `approval depends on service access rather than the scan option or transport`() {
        assertFalse(actions().permissionEnabled)
        assertTrue(actions(running = true, supported = false).permissionEnabled)
        assertTrue(actions(installed = false, running = true).permissionEnabled)
        assertFalse(actions(running = true, allowed = true).permissionEnabled)
        assertFalse(actions(running = true, legacy = true).permissionEnabled)
        assertFalse(actions(running = true, busy = true).permissionEnabled)
    }

    @Test fun `retry cannot substitute for missing setup but recovery works on unsupported transports`() {
        assertFalse(actions(state = State.FAILED).retryEnabled)
        assertFalse(actions(running = true, state = State.FAILED).retryEnabled)
        assertTrue(actions(running = true, allowed = true, state = State.FAILED).retryEnabled)
        assertFalse(actions(running = true, allowed = true, state = State.ACTIVE).retryEnabled)
        assertFalse(actions(running = true, allowed = true, state = State.FAILED, supported = false).retryEnabled)
        assertTrue(actions(running = true, allowed = true, state = State.RECOVERY, supported = false).retryEnabled)
    }
}
