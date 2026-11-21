package com.lenid.zoh.carplay

import com.lenid.zoh.modules.TransportModules

import com.lenid.zoh.modules.ModuleFunction
import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.os.*
import android.view.KeyEvent
import com.lenid.zoh.aap.decodeAlbumArt
import com.lenid.zoh.aap.MediaCenterBridge
import com.lenid.zoh.mirror.ClusterVideo
import com.lenid.zoh.utils.Settings
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.connection.ConnectionArbiter
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A single explicitly started session outlives its UI and every launcher-card resize. */
class DirectCarPlayService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@DirectCarPlayService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var session: AirPlaySession? = null
    private var starting = false
    private var lease: ConnectionArbiter.ExternalLease? = null
    private var generation = 0
    private var reconnectGeneration: Int? = null
    private var reconnectTimeout: Job? = null
    private var surface: Surface? = null
    private var night = false
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()
    internal var canvas = CarPlayViewport(1920, 1080); private set
    internal var areas = listOf(canvas); private set
    internal var areaIndex = 0; private set
    val running get() = starting || controller != null
    val connected get() = session != null

    private var widget: MediaCenterBridge? = null
    private var displayedTrack: com.shilapi.xcertplay.media.CarPlayNowPlaying? = null
    private var artworkJob: Job? = null
    private var displayedArtwork: ByteArray? = null
    private var clusterSurface: Surface? = null
    private val widgetPreference = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Settings.KEY_MEDIA_WIDGET_TARGET) updateWidget()
    }

    private fun updateWidget() {
        val owner = controller
        if (session == null || owner == null || App.provide(this).settings.mediaWidgetTarget != Settings.MEDIA_WIDGET_DIRECT) {
            widget?.stop()
            widget = null
            displayedTrack = null
            displayedArtwork = null
            artworkJob?.cancel()
            return
        }
        if (widget == null) {
            if (!MediaCenterBridge.isAvailable(this)) return
            widget = MediaCenterBridge(this, MediaCenterBridge.SOURCE_TYPE_CARPLAY) { key ->
                val code = ModuleFunction.MEDIA_BUTTON.evaluate(key)
                val button = when (code) {
                    0 -> return@MediaCenterBridge
                    1 -> CarPlayMediaButton.PLAY
                    2 -> CarPlayMediaButton.PAUSE
                    3 -> CarPlayMediaButton.NEXT
                    4 -> CarPlayMediaButton.PREVIOUS
                    else -> when (key) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> CarPlayMediaButton.PLAY
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> CarPlayMediaButton.PAUSE
                        KeyEvent.KEYCODE_MEDIA_NEXT -> CarPlayMediaButton.NEXT
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CarPlayMediaButton.PREVIOUS
                        else -> return@MediaCenterBridge
                    }
                }
                if (controller === owner && connected) owner.sendMediaButton(button)
            }.also { it.start() }
        }
        val media = owner.nowPlayingSnapshot()
        val track = media.copy(playing = false, elapsedMillis = null)
        val artwork = owner.matchingArtworkSnapshot(media.artworkTransferId)?.bytes
        if (track != displayedTrack || artwork !== displayedArtwork) {
            displayedTrack = track
            displayedArtwork = artwork
            artworkJob?.cancel()
            val target = widget ?: return
            target.track(track.title.orEmpty(), track.artist.orEmpty(), track.album.orEmpty(),
                track.durationMillis ?: 0L, null, null)
            if (artwork != null) artworkJob = scope.launch {
                val cover = withContext(Dispatchers.Default) { decodeAlbumArt(artwork) }
                if (cover != null && widget === target && displayedArtwork === artwork) {
                    target.track(track.title.orEmpty(), track.artist.orEmpty(), track.album.orEmpty(),
                        track.durationMillis ?: 0L, cover, artwork.contentHashCode())
                }
            }
        }
        widget?.playState(media.playing, media.elapsedMillis ?: 0L)
    }

    private fun updateClusterSurface() {
        val target = ClusterVideo.companionSurface?.takeIf { it.isValid }
        if (clusterSurface !== target) {
            clusterSurface?.let { sink?.clearSurface(111, it) }
            target?.let { sink?.setSurface(111, it) }
            clusterSurface = target
        }
        controller?.setDashboardMapOutputVisible(target != null)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.lenid.zoh.utils.LocaleHelper.wrapContext(newBase))
    }
    override fun onBind(intent: Intent?) = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        currentService = this
        getSharedPreferences("settings", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(widgetPreference)
        mutableStatus.value = getString(R.string.carplay_ready)
        // Observe the existing status; the diagnostics UI never binds or starts this service.
        scope.launch { status.collect { diagnosticStatus = it } }
        // AA retains priority. Never disconnect AA or alter its preferences from this feature.
        scope.launch {
            App.provide(this@DirectCarPlayService).commManager.connectionState.collect {
                if (running && App.provide(this@DirectCarPlayService).commManager.isBusy) {
                    stopSession()
                    mutableStatus.value = getString(R.string.carplay_disconnect_aa)
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_RECONNECT -> {
                if (running) return START_NOT_STICKY
                showForegroundNotification()
                val address = intent.getStringExtra(DirectCarPlayActivity.EXTRA_HOME_ADDRESS)
                val profile = getSharedPreferences("carplay_reconnect", MODE_PRIVATE)
                val views = profile.getString("views", "").orEmpty().split(",").mapNotNull { pair ->
                    val parts = pair.split("x")
                    val w = parts.firstOrNull()?.toIntOrNull()
                    val h = parts.getOrNull(1)?.toIntOrNull()
                    if (w != null && h != null && w > 0 && h > 0) CarPlayViewport(w, h) else null
                }
                if (address != null && address in Settings.getAutoStartBtMacs(this) &&
                    knownWirelessPhone(this, address) && views.isNotEmpty()) {
                    startSession(true, views.first(), views, address)
                    if (running) {
                        val attempt = generation
                        reconnectGeneration = attempt
                        reconnectTimeout = scope.launch {
                            delay(120_000L)
                            if (generation == attempt && session == null) stopSession()
                        }
                    }
                }
                if (!running) { stopForeground(true); stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }
    internal fun startSession(wireless: Boolean, size: CarPlayViewport, knownWindows: List<CarPlayViewport>, selectedBluetoothAddress: String? = null) {
        if (running) return
        if (App.provide(this).commManager.isBusy) {
            mutableStatus.value = getString(R.string.carplay_disconnect_aa); return
        }
        val identityDir = File(noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!File(identityDir, "identity.pk8").isFile || !File(identityDir, "certificate.p7b").isFile) {
            mutableStatus.value = getString(R.string.carplay_auth_missing); return
        }
        lease = ConnectionArbiter.reserveExternal { App.provide(this).commManager.isBusy }
        if (lease == null) { mutableStatus.value = getString(R.string.carplay_disconnect_aa); return }
        starting = true
        val attempt = ++generation
        try { foreground() } catch (e: Exception) {
            stopSession(); mutableStatus.value = getString(R.string.carplay_start_failed); return
        }
        val ultra = CarPlayOptions.ultra(this)
        val trace = CarPlayOptions.trace(this)
        CarPlayDiagnostics.begin(wireless, ultra)
        mutableStatus.value = getString(R.string.carplay_starting)
        canvas = size
        areas = (listOf(canvas) + knownWindows.map { it.fitIn(canvas) }).distinct()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LocalMfiAuthenticationClient.load(identityDir) }
                if (attempt != generation || !starting || App.provide(this@DirectCarPlayService).commManager.isBusy) {
                    if (attempt == generation) stopSession()
                    return@launch
                }
                val store = CarPlayIdentityStore(this@DirectCarPlayService)
                val identity = withContext(Dispatchers.IO) { store.identity() }
                if (attempt != generation || !starting) return@launch
                val deviceId = identity.publicKey.take(6).mapIndexed { i, b ->
                    val v = if (i == 0) ((b.toInt() and 0xfc) or 2) else b.toInt() and 0xff
                    "%02X".format(v)
                }.joinToString(":")
                val cluster = ClusterVideo.wanted(this@DirectCarPlayService)?.let { shape ->
                    AirPlayDisplayConfig(shape.contentWidth.coerceAtLeast(8), shape.contentHeight.coerceAtLeast(8),
                        fps = 30, primaryInputDevice = 0, features = 0, initialUrl = CarPlayClusterDisplay.MAP_URL)
                }
                val renderer = AndroidMediaSink(surface = null, videoWidth = canvas.width,
                    videoHeight = canvas.height, context = this@DirectCarPlayService, audioFocusEnabled = true)
                sink = renderer
                surface?.let { renderer.setSurface(110, it) }
                val config = AirPlayConfig(transportIo = TransportModules.carPlay, deviceName = "Open Zeekr Headunit", deviceId = deviceId,
                    btMac = deviceId, sourceVersion = "950.7.1", microphone = true,
                    manufacturer = "Open Zeekr Headunit", model = "Zeekr", oemLabel = "Open Headunit",
                    ultraFeatures = if (ultra) CarPlayUltra.DEFAULT_FEATURES else emptySet(),
                    vehicleSettings = if (ultra) listOf(CarPlayVehicleState.SettingLine(
                        id = "version", label = getString(R.string.carplay_vehicle_version), symbol = "info.circle",
                        value = com.lenid.zoh.BuildConfig.VERSION_NAME,
                        section = CarPlayVehicleState.Section("zoh", "Open Zeekr Headunit"))) else emptyList(),
                    cluster = cluster,
                    main = AirPlayDisplayConfig(canvas.width, canvas.height, fps = 30,
                        viewAreas = areas.map { AirPlayViewArea(it.width, it.height) }))
                val labPort = if (com.lenid.zoh.BuildConfig.DEBUG &&
                    android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) {
                    File(noBackupFilesDir, "carplay-usb-bridge.port").takeIf { it.isFile }
                        ?.readText()?.trim()?.toIntOrNull()?.takeIf { it in 1024..65535 }
                } else null
                val runtime = CarPlayRuntimeConfig(labUsbBridgePort = labPort, mfiTarget = MfiTarget.LOCAL,
                    wirelessBluetoothDeviceAddress = selectedBluetoothAddress,
                    firstTcpTimeoutMillis = com.lenid.zoh.utils.ModuleProfile.CARPLAY.value.toLong(),
                    identification = Iap2IdentificationConfig("Open Zeekr Headunit", "Zeekr", "Open Headunit",
                        identity.pairingId, "1.0", "1.0", 3, externalAccessoryProtocol = "com.lenid.zoh.carplay"),
                    label = "Open Zeekr Headunit", hostName = "open-zeekr-carplay",
                    transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED)
                controller = CarPlayController(this@DirectCarPlayService, runtime, config, identity, store.pairings(),
                    object : AirPlaySessionListener {
                        override val wantsTrace = trace
                        override fun onSessionActive(active: AirPlaySession) { scope.launch {
                            if (attempt != generation) return@launch
                            reconnectTimeout?.cancel()
                            session = active
                            if (wireless) controller?.connectedWirelessAddress?.let(::rememberWirelessPhone)
                            updateWidget()
                            com.lenid.zoh.utils.ConnectionJournal.record(this@DirectCarPlayService, "CP:connected")
                            scope.launch(Dispatchers.IO) { active.setNightMode(night) }
                            controller?.showViewArea(areaIndex)
                            mutableStatus.value = getString(R.string.carplay_connected)
                        } }
                        override fun onSessionEnded(active: AirPlaySession) { scope.launch {
                            if (session !== active) return@launch
                            if (reconnectGeneration == attempt) {
                                stopSession()
                            } else {
                                com.lenid.zoh.utils.ConnectionJournal.record(this@DirectCarPlayService, "CP:disconnected")
                                session = null
                                updateWidget()
                                mutableStatus.value = getString(R.string.carplay_waiting)
                            }
                        } }
                        override fun onHostUiRequested(session: AirPlaySession) {
                            // Do not open a fullscreen activity from an iPhone Home event.
                        }
                    }, CarPlayMediaEngine(renderer, microphoneEnabled = true), reportStatus = { state ->
                        if (attempt == generation) {
                            CarPlayDiagnostics.record(state)
                            mutableStatus.value = getString(if (session != null && state !is CarPlayStatus.Failed)
                                R.string.carplay_connected else CarPlayDiagnostics.message(state))
                        }
                    }, loadPairRecord = store::lockdown, savePairRecord = store::saveLockdown,
                    clearPairRecord = store::clearLockdown, connectionAction = { ModuleFunction.CONNECT_ACTION.evaluate(it) })
                controller?.artworkListener = { _, _ -> scope.launch { if (attempt == generation) updateWidget() } }
                controller?.nowPlayingListener = { scope.launch { if (attempt == generation) updateWidget() } }
                updateClusterSurface()
                starting = false
                controller?.start()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CarPlayDiagnostics.record(CarPlayStatus.Failed(e.message ?: "Startup"))
                com.lenid.zoh.utils.AppLog.e("DirectCarPlay: startup failed", e)
                if (attempt == generation) { stopSession(); mutableStatus.value = getString(R.string.carplay_start_failed) }
            }
        }
    }
    fun attachSurface(value: Surface) { surface = value; sink?.setSurface(110, value) }
    fun detachSurface(value: Surface) {
        if (surface !== value) return
        surface = null
        sink?.beginSurfaceDetach(value, parkMain = true)?.await()
    }
    internal fun selectArea(window: CarPlayViewport): CarPlayViewport {
        val next = window.closest(areas)
        if (areaIndex != next) { areaIndex = next; controller?.showViewArea(next) }
        return areas[areaIndex]
    }
    fun touch(contacts: List<AirPlayContact>) { controller?.sendTouch(contacts) }
    fun themeChanged(isNight: Boolean) { night = isNight; val active = session
        scope.launch(Dispatchers.IO) { active?.setNightMode(isNight) }
    }
    fun stopSession() {
        reconnectTimeout?.cancel()
        reconnectTimeout = null
        reconnectGeneration = null
        if (session != null) com.lenid.zoh.utils.ConnectionJournal.record(this, "CP:disconnected")
        generation++; starting = false
        val oldController = controller; val oldSink = sink
        controller = null; sink = null; session = null; areaIndex = 0
        clusterSurface = null
        updateWidget()
        closeTransport(oldController, oldSink)
        stopForeground(true)
        stopSelf()
        mutableStatus.value = getString(R.string.carplay_ready)
    }
    override fun onDestroy() {
        val oldController = controller; val oldSink = sink
        generation++; controller = null; sink = null; session = null
        clusterSurface = null
        updateWidget()
        closeTransport(oldController, oldSink)
        scope.cancel()
        if (currentService === this) currentService = null
        diagnosticStatus = ""
        getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(widgetPreference)
        super.onDestroy()
    }
    private fun closeTransport(oldController: CarPlayController?, oldSink: AndroidMediaSink?) {
        val oldLease = lease; lease = null
        oldController?.close()
        Thread {
            // Do not rearm AA while CarPlay can still remove a Wi-Fi group or VPN route.
            while (oldController != null && !oldController.awaitClosed(1000)) {
                if (Thread.currentThread().isInterrupted) return@Thread
            }
            try { oldSink?.close() } finally { oldLease?.let(ConnectionArbiter::releaseExternal) }
        }.apply { isDaemon = true; name = "CarPlay teardown" }.start()
    }
    private fun foreground() {
        startService(Intent(this, javaClass))
        showForegroundNotification()
    }

    private fun showForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("direct-carplay", "CarPlay", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(7123, NotificationCompat.Builder(this, "direct-carplay")
            .setSmallIcon(R.drawable.ic_carplay).setContentTitle("CarPlay")
            .setContentText(getString(R.string.carplay_notification)).setOngoing(true)
            .addAction(0, getString(R.string.carplay_stop), stop).build())
    }
    private fun rememberWirelessPhone(address: String) {
        val prefs = getSharedPreferences("carplay_reconnect", MODE_PRIVATE)
        val phones = prefs.getStringSet("phones", emptySet()).orEmpty()
        prefs.edit().putStringSet("phones", phones + address)
            .putString("views", areas.joinToString(",") { "${it.width}x${it.height}" }).apply()
    }

    companion object {
        @Volatile private var currentService: DirectCarPlayService? = null
        fun knownWirelessPhone(context: android.content.Context, address: String): Boolean =
            address in context.getSharedPreferences("carplay_reconnect", MODE_PRIVATE)
                .getStringSet("phones", emptySet()).orEmpty()
        fun clusterSurfaceChanged() {
            currentService?.let { service -> service.scope.launch { service.updateClusterSurface() } }
        }
        fun clusterStreaming() = currentService?.controller?.dashboardMapStreaming() == true
        fun clusterSize() = currentService?.controller?.configuredClusterSize()
        fun sessionRunning(): Boolean = currentService?.running == true
        fun refreshCurrentPicture(): Boolean {
            val service = currentService ?: return false
            if (!service.connected || service.surface?.isValid != true) return false
            service.sink?.refreshPicture(110) ?: return false
            return true
        }
        internal const val ACTION_RECONNECT = "com.lenid.zoh.carplay.RECONNECT"
        private const val ACTION_STOP = "com.lenid.zoh.carplay.STOP"
        @Volatile var diagnosticStatus: String = ""
            private set
    }
}
