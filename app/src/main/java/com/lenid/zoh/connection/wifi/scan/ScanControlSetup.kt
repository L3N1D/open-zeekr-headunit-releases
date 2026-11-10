package com.lenid.zoh.connection.wifi.scan

import com.lenid.zoh.R
import com.lenid.zoh.connection.wifi.scan.WifiScanControl.State

/** Setup is independent of the transport that will eventually use Shizuku. */
internal object ScanControlSetup {
    data class Actions(
        val managerLabel: Int,
        val managerEnabled: Boolean,
        val permissionEnabled: Boolean,
        val retryEnabled: Boolean
    )

    fun actions(installed: Boolean, running: Boolean, allowed: Boolean, legacy: Boolean,
                busy: Boolean, state: State,
                scanSupported: Boolean): Actions {
        val changing = busy || state == State.WORKING
        return Actions(
            managerLabel = if (installed) R.string.wifi_scan_open_shizuku else R.string.wifi_scan_install,
            managerEnabled = !changing,
            permissionEnabled = !changing && running && !legacy && !allowed,
            retryEnabled = !changing && running && !legacy && allowed &&
                (state == State.RECOVERY || (scanSupported && state in setOf(State.FAILED, State.CHANGED)))
        )
    }
}
