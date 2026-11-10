package com.lenid.zoh.connection.wifi.modes

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lenid.zoh.App
import com.lenid.zoh.connection.CommManager
import com.lenid.zoh.connection.ConnectionStage
import com.lenid.zoh.connection.ConnectionStageTracker
import com.lenid.zoh.connection.wifi.MacAddressPolicy
import com.lenid.zoh.connection.wifi.WifiLauncher
import com.lenid.zoh.connection.wifi.WifiLauncherManager
import com.lenid.zoh.connection.wifi.WifiLauncherMode
import com.lenid.zoh.connection.wifi.WifiLauncherStopSequence
import com.lenid.zoh.connection.wifi.direct.GroupIdentityStability
import com.lenid.zoh.connection.wifi.direct.GroupIdentityStabilityPolicy
import com.lenid.zoh.connection.wifi.direct.StationStandDown
import com.lenid.zoh.connection.wifi.direct.StationStandDownSettlePolicy
import com.lenid.zoh.connection.wifi.direct.WifiDirectManager
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeAaHandshakeManager
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeCredentialsPolicy
import com.lenid.zoh.connection.wifi.modes.nativeaa.NativeStrategy
import com.lenid.zoh.connection.wifi.modes.nativeaa.SoftApEndpointStabilityPolicy
import com.lenid.zoh.main.SettingsActivity
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.BluetoothHelper
import com.lenid.zoh.utils.ConnectionIssue
import com.lenid.zoh.utils.ConnectionIssues
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WifiLauncherNative : WifiLauncher {

    private companion object {
        /** Safety net over the teardown's own settle budget, so a hotspot that will not go never strands a bring-up. */
        private const val HOTSPOT_TEARDOWN_CEILING_MS = 10_000L
    }

    val strategy: NativeStrategy

    var handshakeManager: NativeAaHandshakeManager? = null
        private set

    constructor(manager: WifiLauncherManager) : super(manager) {
        // copy settings early in construction to align with #hasSameStartConfiguration
        this.strategy = settings.nativeApStrategy
    }

    constructor(manager: WifiLauncherManager, strategy: NativeStrategy) : super(manager) {
        this.strategy = strategy
    }

    override val mode = WifiLauncherMode.NATIVE

    override fun hasSameStartConfiguration(launcher: WifiLauncher) = launcher is WifiLauncherNative && launcher.strategy == strategy

    override fun hasWifiDirect() = strategy == NativeStrategy.WIFI_DIRECT

    override fun hostsOwnAccessPoint() = false

    // Both transports, not just the P2P one. The credentials this mode hands the phone name
    // port 5288 whichever network carries them, and the phone dials it the moment it has
    // joined. Gated on the strategy, the hotspot route bound nothing until the handshake
    // noticed and repaired it, so every attempt paid the port wait first and a phone
    // reconnecting on credentials it already had found nothing listening at all.
    override fun hasWirelessServer() = true


    override fun start(noInfoToasts: Boolean) {
        val wifiDirect = manager.sharedServices.wifiDirectManager

        handshakeManager = NativeAaHandshakeManager(service, this, service.serviceScope)
        // The wake poke wakes the phone, and a phone that answers takes over the screen. Doing that
        // to somebody who is in the middle of changing settings loses whatever they were reading.
        handshakeManager?.userConfiguringProvider = { SettingsActivity.isForeground }

        if (wifiDirect != null) {
            // Before the group, not after: wpa_supplicant only honours a channel while no group
            // exists, and an associated station is what leaves it none to give. A no-op on a
            // unit that is not joined to anything.
            val stoodDown = StationStandDown.standDown(service)

            // Start WiFi Direct as a "quiet host" (P2P Group for phone to join)
            // We let WifiDirectManager handle the WiFi state (enabling if needed)
            setupWifiDirect(wifiDirect)
            if (stoodDown) {
                // Claimed before the wait, not inside startNativeAaQuietHost: the poke's
                // pre-flight refresh lands well inside this window, and with nothing marked
                // in flight it remade a group underneath the one about to be asked for, and
                // skipped the stand-down doing it.
                wifiDirect.claimNativeCreateWindow("waiting for this unit to leave its own network")
                // A group asked for while the station is still tearing down forms on the
                // channel the stand-down was meant to free, and stays there. Ask whether it
                // has left rather than assuming the whole window is needed; the claim above
                // is held across every poll, so nothing else starts a create in the gap.
                awaitStandDownThenCreate(wifiDirect, waitedMs = 0L)
            } else {
                createWhenRadioIsFree(wifiDirect)
            }
        }

        // Start the official Bluetooth handshake servers
        handshakeManager?.start()

        // The listeners are open now, so the phone can be woken while the group forms rather
        // than after it. Refused unless there is genuinely nothing else to wait for.
        handshakeManager?.triggerEarlyWake(service.userExitedAA)
    }

    /**
     * Creates the group as soon as the station has actually left, or at the ceiling, whichever
     * comes first. The create window stays claimed for every poll.
     */
    private fun awaitStandDownThenCreate(wifiDirect: WifiDirectManager, waitedMs: Long) {
        if (manager.active !== this || manager.sharedServices.wifiDirectManager !== wifiDirect) return

        val stillAssociated = StationStandDown.isStillAssociated(service)
        when (StationStandDownSettlePolicy.step(stillAssociated, waitedMs)) {
            StationStandDownSettlePolicy.Step.WAIT ->
                Handler(Looper.getMainLooper()).postDelayed(
                    { awaitStandDownThenCreate(wifiDirect, waitedMs + StationStandDownSettlePolicy.POLL_MS) },
                    StationStandDownSettlePolicy.POLL_MS
                )
            StationStandDownSettlePolicy.Step.CREATE -> {
                AppLog.i("WifiLauncherNative: creating the group ${waitedMs}ms after the stand-down (still joined=$stillAssociated).")
                createWhenRadioIsFree(wifiDirect)
            }
        }
    }

    /**
     * Creates the group once the hotspot teardown that frees this radio has actually finished.
     *
     * A single-radio chip cannot host a group, or even be asked to switch WiFi on, while its own
     * access point still holds it — and that teardown is started moments earlier on another thread.
     */
    private fun createWhenRadioIsFree(wifiDirect: WifiDirectManager) {
        val teardown = manager.sharedServices.hotspotTeardown
        if (teardown == null || teardown.isCompleted) {
            wifiDirect.startNativeAaQuietHost()
            return
        }
        AppLog.i("WifiLauncherNative: waiting for this unit's hotspot to go down before creating the group.")
        // Claimed before the wait, exactly as the stand-down branch does: tearing the hotspot down
        // cycles the P2P interface, and the ENABLED that follows lands in this gap and starts a
        // second bring-up whose BUSY removes the group this one is about to make.
        wifiDirect.claimNativeCreateWindow("waiting for this unit's hotspot to go down")
        service.serviceScope.launch {
            val freed = withTimeoutOrNull(HOTSPOT_TEARDOWN_CEILING_MS) { teardown.join() } != null
            if (manager.active !== this@WifiLauncherNative ||
                manager.sharedServices.wifiDirectManager !== wifiDirect
            ) {
                wifiDirect.releaseNativeCreateWindow("the launcher was replaced while its hotspot went down")
                return@launch
            }
            if (!freed) {
                AppLog.w(
                    "WifiLauncherNative: this unit's hotspot had not gone down after " +
                        "${HOTSPOT_TEARDOWN_CEILING_MS / 1000}s; creating the group anyway."
                )
            }
            wifiDirect.startNativeAaQuietHost()
        }
    }

    override fun stop(seq: WifiLauncherStopSequence) {
        if (seq.handledAt(WifiLauncherStopSequence.LAST)) {
            handshakeManager?.stop()
            // Whatever the mode is switching to, this unit gets its own network back. AapService
            // restores as well, because a force-stop never reaches here at all.
            StationStandDown.restore(service)
        }
    }

    /** A phone given an endpoint at a group IP that has since moved dials the old one forever. */
    @Synchronized
    private fun noteAdvertisedGroupAddressMoved(ssid: String, psk: String, ip: String) {
        val advertised = settings.wifiDirectAdvertisedEndpoint ?: return
        if (!SoftApEndpointStabilityPolicy.sameNetwork(advertised, ssid, psk)) {
            settings.wifiDirectAdvertisedEndpoint = null
            return
        }
        val moved = SoftApEndpointStabilityPolicy.groupAddressMoved(advertised, ssid, psk, ip) ?: return
        settings.wifiDirectAdvertisedEndpoint = null
        AppLog.w(
            "NativeAA: the WPP endpoint advertised on the WiFi Direct group at ${advertised.ip} no longer " +
                "matches ($moved); a phone holding it needs this head unit forgotten in Android Auto."
        )
        ConnectionIssues.raiseOnce(service, ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT)
    }



    private fun setupWifiDirect(wifiDirectManager: WifiDirectManager) {
        val commManager = App.provide(service).commManager

        wifiDirectManager.setCredentialsListener { ssid, psk, ip, bssid, identity ->
            noteAdvertisedGroupAddressMoved(ssid, psk, ip)
            onNativeCredentials(ssid, psk, ip, bssid, identity)
        }

        // Settling counts as in-flight here: isHandshakeInFlight() goes false the instant Type 3
        // is written, but the phone still has to associate, do WPS and get a DHCP lease, and
        // recreating the group in that window hands it an SSID it can no longer join.
        wifiDirectManager.setNativeHandshakeStateProvider {
            handshakeManager?.isHandshakeInFlight() == true ||
            handshakeManager?.isHandoffSettling() == true
        }
        wifiDirectManager.setNativeSessionConnectedProvider { commManager.isConnected }
        // The join watchdog recreates a group a phone could not join. A phone that has not opened the
        // Bluetooth channel since we armed was never handed these credentials, so there is nothing to
        // repair — including on a reconnect, where the last session's dial says nothing about this one.
        wifiDirectManager.setPhoneEverOpenedAaChannelProvider {
            handshakeManager?.hasPhoneOpenedAaChannelThisArming() == true
        }
        // A group that carried a session is normally never recreated. This is how the watchdog
        // learns the phone has stopped coming back to it. See ProvenGroupStalePolicy.
        wifiDirectManager.setUnansweredPokeCountProvider {
            handshakeManager?.unansweredPokeCount() ?: 0
        }
        wifiDirectManager.setNativeGroupInvalidatedListener { handshakeManager?.invalidateCredentials() }
    }

    /** Whether the WiFi Direct group the phone is told to join is up; null without a manager. */
    fun hasLiveNetwork(): Boolean? = manager.sharedServices.wifiDirectManager?.hasLiveGroup

    /** Whether a group has been asked for and has not answered yet; null without a manager. */
    fun networkComingUp(): Boolean? = manager.sharedServices.wifiDirectManager?.isCreatingGroup

    /**
     * A projection session has landed. The wake poke has nothing left to do, and a P2P group
     * that carried a wireless session is proven joinable. A wired session proves nothing about
     * the group.
     */
    fun onSessionEstablished() {
        handshakeManager?.onSessionEstablished()
        if (App.provide(service).commManager.isWirelessSession) {
            manager.sharedServices.wifiDirectManager?.noteSessionHosted()
        }
    }

    /**
     * Puts the mode back where it was before the session, without taking the network down.
     *
     * [wakePhone] is false when the phone ended the session itself, which is the one case where
     * waking it pulls it back against its own choice.
     */
    fun rearmAfterSessionEnd(wakePhone: Boolean) {
        handshakeManager?.noteSessionEnded(wakePhone)
        reopenListeners()
    }

    /**
     * Reopens the Bluetooth and TCP sides without treating this as a session ending.
     *
     * The network is what the phone saved and rejoins, so it stays; only the Bluetooth side needs
     * re-arming, because a completed handoff closed its Android Auto listeners. The credentials
     * are then read again, which confirms the network is still up and restarts the wake poke. The
     * TCP port is checked first: it is what the phone dials, and nothing else looks at it between
     * sessions.
     */
    fun reopenListeners() {
        manager.sharedServices.startWirelessServer(this)
        handshakeManager?.rearmForNextSession()
        triggerWifiDirectRefresh()
    }

    /**
     * After a sleep: checks the TCP port and re-reads the network, and nothing else. Unlike
     * [reopenListeners] it keeps the driver-selection and handshake state a user may have set.
     */
    fun refreshAfterWake() {
        manager.sharedServices.startWirelessServer(this)
        triggerWifiDirectRefresh()
    }

    /**
     * Triggers a refresh of the WiFi Direct "quiet host" state.
     * Called by NativeAaHandshakeManager if it's waiting for credentials that haven't arrived yet.
     */
    fun triggerWifiDirectRefresh() {
        // Read again, not remade: see WifiDirectManager.refreshNativeCredentials.
        AppLog.i("AapService: WiFi Direct credential refresh requested.")
        manager.sharedServices.wifiDirectManager?.refreshNativeCredentials()
    }

    /**
     * Credentials for the network the phone should join, from whichever transport produced them.
     * Both mode-3 transports funnel through here so the poke rules stay in one place.
     */
    private fun onNativeCredentials(
        ssid: String,
        psk: String,
        ip: String,
        bssid: String,
        identity: GroupIdentityStability,
    ) {
        val commManager = App.provide(service).commManager

        if (settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
            AppLog.d("AapService: WiFi credentials received, but not in Native AA mode. Skipping HandshakeManager update.")
            return
        }

        AppLog.i("AapService: Received WiFi credentials from manager (SSID=$ssid, IP=$ip). Updating and Triggering Poke.")
        handshakeManager?.updateWifiCredentials(ssid, psk, ip, bssid, identity)

        if (commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
            AppLog.i("AapService: USB/other session already active. Skipping auto-poke to avoid pulling phone into wireless flow.")
        } else if (!NativeCredentialsPolicy.isUsablePassphrase(psk)) {
            // A poke takes the phone's hands-free link and nothing gives it back, so it is not
            // spent on a network the phone will refuse. The handshake says what to set.
            AppLog.w("AapService: not waking the phone for '$ssid', which has no passphrase to join with.")
        } else if (handshakeManager?.wakesPhone() == false) {
            // The pill is reported here as well as poked, so both stand down together or it claims
            // a wake that will not run.
            AppLog.i("AapService: automatic wake is paused by the session policy. Keeping listeners available.")
        } else if (!service.userExitedAA) {
            ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
            handshakeManager?.triggerPoke()
        } else {
            AppLog.i("AapService: userExitedAA is true. Skipping auto-poke.")
        }
    }

    /**
     * Whether the AAP TCP port the phone will be sent to is bound and accepting.
     *
     * The Bluetooth handshake checks this before handing over credentials, mirroring the ordering
     * the reference head unit software uses: access point up, address resolved, port bound, and
     * only then talk to the phone.
     */
    fun isWirelessServerListening(): Boolean = manager.sharedServices.wirelessServer?.isListening == true
}
