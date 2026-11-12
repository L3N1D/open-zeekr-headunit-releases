package com.lenid.zoh.carplay

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.os.*
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
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var session: AirPlaySession? = null
    private var starting = false
    private var lease: ConnectionArbiter.ExternalLease? = null
    private var generation = 0
    private var surface: Surface? = null
    private var night = false
    private val mutableStatus = MutableStateFlow("")
    val status = mutableStatus.asStateFlow()
    internal var canvas = CarPlayViewport(1920, 1080); private set
    internal var areas = listOf(canvas); private set
    internal var areaIndex = 0; private set
    val running get() = starting || controller != null
    val connected get() = session != null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.lenid.zoh.utils.LocaleHelper.wrapContext(newBase))
    }
    override fun onBind(intent: Intent?) = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        mutableStatus.value = getString(R.string.carplay_ready)
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
        if (intent?.action == ACTION_STOP) { stopSession(); stopSelf() }
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
                val renderer = AndroidMediaSink(surface = surface, videoWidth = canvas.width,
                    videoHeight = canvas.height, context = this@DirectCarPlayService, audioFocusEnabled = true)
                sink = renderer
                val config = AirPlayConfig(deviceName = "Open Zeekr Headunit", deviceId = deviceId,
                    btMac = deviceId, sourceVersion = "950.7.1", microphone = true,
                    manufacturer = "Open Zeekr Headunit", model = "Zeekr", oemLabel = "Open Headunit",
                    ultraFeatures = if (ultra) CarPlayUltra.DEFAULT_FEATURES else emptySet(),
                    vehicleSettings = if (ultra) listOf(CarPlayVehicleState.SettingLine(
                        id = "version", label = getString(R.string.carplay_vehicle_version), symbol = "info.circle",
                        value = com.lenid.zoh.BuildConfig.VERSION_NAME,
                        section = CarPlayVehicleState.Section("zoh", "Open Zeekr Headunit"))) else emptyList(),
                    main = AirPlayDisplayConfig(canvas.width, canvas.height, fps = 30,
                        viewAreas = areas.map { AirPlayViewArea(it.width, it.height) }))
                val labPort = if (com.lenid.zoh.BuildConfig.DEBUG &&
                    android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) {
                    File(noBackupFilesDir, "carplay-usb-bridge.port").takeIf { it.isFile }
                        ?.readText()?.trim()?.toIntOrNull()?.takeIf { it in 1024..65535 }
                } else null
                val runtime = CarPlayRuntimeConfig(labUsbBridgePort = labPort, mfiTarget = MfiTarget.LOCAL,
                    wirelessBluetoothDeviceAddress = selectedBluetoothAddress,
                    identification = Iap2IdentificationConfig("Open Zeekr Headunit", "Zeekr", "Open Headunit",
                        identity.pairingId, "1.0", "1.0", 3, externalAccessoryProtocol = "com.lenid.zoh.carplay"),
                    label = "Open Zeekr Headunit", hostName = "open-zeekr-carplay",
                    transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED)
                controller = CarPlayController(this@DirectCarPlayService, runtime, config, identity, store.pairings(),
                    object : AirPlaySessionListener {
                        override val wantsTrace = trace
                        override fun onSessionActive(active: AirPlaySession) { scope.launch {
                            if (attempt != generation) return@launch
                            session = active
                            scope.launch(Dispatchers.IO) { active.setNightMode(night) }
                            controller?.showViewArea(areaIndex)
                            mutableStatus.value = getString(R.string.carplay_connected)
                        } }
                        override fun onSessionEnded(active: AirPlaySession) { scope.launch {
                            if (session === active) { session = null; mutableStatus.value = getString(R.string.carplay_waiting) }
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
                    clearPairRecord = store::clearLockdown)
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
        generation++; starting = false
        val oldController = controller; val oldSink = sink
        controller = null; sink = null; session = null; areaIndex = 0
        closeTransport(oldController, oldSink)
        stopForeground(true)
        stopSelf()
        mutableStatus.value = getString(R.string.carplay_ready)
    }
    override fun onDestroy() {
        val oldController = controller; val oldSink = sink
        generation++; controller = null; sink = null; session = null
        closeTransport(oldController, oldSink)
        scope.cancel()
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
    companion object { private const val ACTION_STOP = "com.lenid.zoh.carplay.STOP" }
}
