package com.lenid.zoh.connection.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.lenid.zoh.App
import com.lenid.zoh.aap.AapService
import com.lenid.zoh.aap.AapService.Companion.scanningState
import com.lenid.zoh.connection.ConnectionStage
import com.lenid.zoh.connection.ConnectionStageTracker
import com.lenid.zoh.connection.HeldServerSocket
import com.lenid.zoh.connection.SameEndpointConnectPolicy
import com.lenid.zoh.connection.UnresponsivePeerPolicy
import com.lenid.zoh.connection.wifi.direct.WifiDirectManager
import com.lenid.zoh.connection.wifi.direct.WifiRadioSwitchPolicy
import com.lenid.zoh.connection.wifi.server.WirelessServer
import com.lenid.zoh.connection.wifi.server.WirelessServerHistory
import com.lenid.zoh.connection.wifi.server.WirelessServerRestartPolicy
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.HotspotManager
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WifiLauncherSharedServices(val service: AapService) {

    var wifiDirectManager: WifiDirectManager? = null
        private set

    var wirelessServer: WirelessServer? = null
        private set
    val wirelessServerHistory = WirelessServerHistory()

    /**
     * The hotspot teardown a WiFi Direct bring-up has to run *behind*, or null when none is pending.
     *
     * Posted to the main looper and never waited for, this ran after the create it was meant to
     * clear the radio for: on a single-radio unit the group was asked for, and WiFi asked to come
     * on, while the access point still held the chip. Callers join it instead.
     */
    @Volatile
    var hotspotTeardown: Job? = null
        private set

    fun update(active: WifiLauncher) {
        if (active.hasWifiDirect()) startWifiDirect() else stopWifiDirect()
        if (active.hasWirelessServer()) startWirelessServer(active) else stopWirelessServer()
    }

    fun stopAll(keepGroup: Boolean = false) {
        stopWifiDirect(keepGroup)
        stopWirelessServer()
    }

    private fun startWifiDirect() {
        if (wifiDirectManager != null)
            stopWifiDirect() // reset from previous session

        wifiDirectManager = WifiDirectManager(service)

        // This chipset potentially can't run SoftAP and WiFi Direct concurrently — make sure hotspot
        // is off before P2P starts. On IO because the wait for it to actually go is a blocking one.
        // Skipped when no group can ever form: freeing the radio for one buys nothing, and on a
        // single-radio unit it takes down the only network that was working.
        val wifiManager =
            service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val radioBlockedOff = wifiManager != null &&
            WifiRadioSwitchPolicy.isBlockedOff(wifiManager.isWifiEnabled, Build.VERSION.SDK_INT)
        if (radioBlockedOff) {
            AppLog.i(
                "AapService: WiFi is off and this Android does not let an app switch it on, so no " +
                    "WiFi Direct group can be created — leaving this unit's hotspot alone."
            )
            hotspotTeardown = null
        } else {
            hotspotTeardown = service.serviceScope.launch(Dispatchers.IO) {
                AppLog.i("AapService: Mode requires WiFi Direct — ensuring hotspot is disabled first...")
                HotspotManager.disableAndAwaitDown(service)
            }
        }

        wifiDirectManager?.setCredentialsListener { _, _, _, _, _ ->
            AppLog.d("AapService: WiFi credentials received, but not in Native AA mode. Skipping HandshakeManager update.")
        }
    }

    private fun stopWifiDirect(keepGroup: Boolean = false) {
        wifiDirectManager?.stop(keepGroup)
        wifiDirectManager = null
        // The teardown itself is left to finish - the access point should stay down for whatever
        // comes next - but nothing may wait on a job belonging to a mode that has gone.
        hotspotTeardown = null
    }

    fun startWirelessServer(launcher: WifiLauncher) {
        val commManager = App.provide(service).commManager
        val existing = wirelessServer
        val action = WirelessServerRestartPolicy.decide(
            assigned = existing != null,
            alive = existing?.isAlive == true,
            listening = existing?.isListening == true,
            nowMs = SystemClock.elapsedRealtime(),
            sessionBusy = commManager.isConnected,
            history = wirelessServerHistory
        )
        val why = WirelessServerRestartPolicy.describe(
            action,
            existing != null,
            existing?.isListening == true,
        )
        when (action) {
            WirelessServerRestartPolicy.Action.NO_OP,
            WirelessServerRestartPolicy.Action.AWAIT,
                -> {
                AppLog.d("AapService: Wireless server not started - $why.")
                return
            }

            WirelessServerRestartPolicy.Action.BACKOFF -> {
                // INFO, not DEBUG. This is the state a stuck unit sits in, and the reporter logs
                // that would have identified it are captured at INFO.
                AppLog.i("AapService: Wireless server on 5288 is not accepting connections - $why.")
                return
            }

            WirelessServerRestartPolicy.Action.REBUILD -> {
                wirelessServerHistory.attempt()
                AppLog.w("AapService: Rebuilding the wireless server on 5288 - $why (attempt ${wirelessServerHistory.rebuildsInWindow}).")
                // Only this object, never stopWirelessServer(): that also clears activeWifiMode and
                // activeHelperStrategy, and the mode has not changed - we are repairing inside it.
                try {
                    existing?.stopServer()
                } catch (e: Exception) {
                    AppLog.d("AapService: Error stopping the previous wireless server: ${e.message}")
                }
                wirelessServer = null
            }

            WirelessServerRestartPolicy.Action.START -> {
                AppLog.d("AapService: Starting the wireless server on 5288 - $why.")
            }
        }

        // Native AA hands the phone our address directly, so nothing needs an mDNS record.
        val shouldRegisterNsd = false

        wirelessServer = WirelessServer(
            shouldRegisterNsd,
            service,
            wirelessServerHistory,
        ).apply { start() }
    }

    private fun stopWirelessServer() {
        if (wirelessServer == null)
            return

        wirelessServer?.stopServer()
        wirelessServer = null
        scanningState.value = false
        // No VpnControl.stopVpn() here. The dummy VPN belongs to Self Mode or to the session that
        // asked for it, never to the wireless server, and update() runs this on every mode change
        // - which is what took a user's VPN down three milliseconds after it came up. The owning
        // teardowns call AapService.stopDummyVpn(); see DummyVpnPolicy.
    }


}
