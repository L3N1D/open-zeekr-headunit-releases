package com.lenid.zoh.dongle

import com.lenid.zoh.connection.usb.UsbSelfGrant
import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The AutoKit (Carlinkit CPC200) dongle session. It lives apart from AapService: the dongle runs
 * Android Auto or CarPlay itself and sends finished H.264 and PCM, so none of the AA stack is
 * involved. It keeps playing with the dongle screen closed, like the AA session does after Exit,
 * and stops when the dongle is unplugged or the driver turns it off in the dongle tab.
 */
class DongleService : Service() {

    /** [waitingOnDriver]: nothing happens until the driver plugs the dongle in or allows access. */
    data class State(val active: Boolean = false, val status: String = "", val phoneConnected: Boolean = false,
                     val waitingOnDriver: Boolean = false)

    interface Listener {
        fun onFrameSize(width: Int, height: Int)
    }

    inner class LocalBinder : Binder() { val service get() = this@DongleService }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbManager
    private lateinit var session: MediaSession
    private lateinit var settings: DongleSettings
    @Volatile private var dongle: DongleSession? = null
    @Volatile private var audio: DongleAudio? = null
    @Volatile private var connecting = false
    /** False until the dongle answers; a TCP connect alone does not mean the bridge reached it. */
    @Volatile private var dongleAnswered = false
    private val connector = Executors.newSingleThreadExecutor()
    private val video = DongleVideo({ requestKeyframe() }) { w, h ->
        main.post { frameW = w; frameH = h; listener?.onFrameSize(w, h) }
    }
    var frameW = 0
        private set
    var frameH = 0
        private set
    private var lastKeyframeRequest = 0L
    private var permissionAsked: UsbDevice? = null
    @Volatile private var lastConnectError: String? = null
    /** The dongle screen's size once it is laid out; until then the display's. */
    private var screenW = 0
    private var screenH = 0
    private var config: DongleConfig? = null
    private var phoneConnected = false
    private var status = ""

    var listener: Listener? = null

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(USB_SERVICE) as UsbManager
        settings = DongleSettings(this)
        startForegroundNotification()
        setupMediaSession()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        ContextCompat.registerReceiver(this, usbReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSharedPreferences(DongleSettings.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(real)
        screenW = real.widthPixels; screenH = real.heightPixels
        applyConfig()
        setStatus(getString(R.string.dongle_plug_in), false, waitingOnDriver = true)
        mutableState.value = mutableState.value.copy(active = true)
        main.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RECONNECT -> reconnect()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        getSharedPreferences(DongleSettings.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        dongle?.close()
        video.release()
        session.release()
        connector.shutdown()
        mutableState.value = State()
        info.clear()
        super.onDestroy()
    }

    fun setSurface(s: Surface?) = video.setSurface(s)

    fun hasVideo() = video.hasFrames

    fun touch(action: Int, x: Float, y: Float) = dongle?.send(DongleProtocol.TOUCH, DongleProtocol.touch(action, x, y))

    fun command(c: Int) = dongle?.command(c)

    /** Re-run the handshake; the poll connects again within a second. */
    fun reconnect() { dongle?.close() }

    /** The dongle screen tells its size, so the phone draws for exactly that area. */
    fun setScreenSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == screenW && height == screenH)) return
        screenW = width; screenH = height
        applyConfig()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyConfig()
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "debug_bridge" || key == "bridge_host") { permissionAsked = null; reconnect() }
        else applyConfig()
    }

    private fun isNight() = when (settings.nightMode) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Build the dongle config from the settings. By default ask for a stream matching the screen
     * (max 1920 wide; the surface scales it up). A changed night mode is sent live, anything else
     * needs a new handshake.
     */
    private fun applyConfig() {
        if (screenW == 0 || screenH == 0) return
        val scale = minOf(1f, 1920f / screenW, 1080f / screenH)
        val fixed = settings.resolution.split("x").mapNotNull { it.toIntOrNull() }
        val (w, h) = if (fixed.size == 2) fixed[0] to fixed[1]
            else ((screenW * scale).toInt() / 8) * 8 to ((screenH * scale).toInt() / 8) * 8
        val dpi = if (settings.dpi > 0) settings.dpi
            else (resources.displayMetrics.densityDpi * scale).toInt().coerceIn(120, 320)
        val cfg = DongleConfig(w, h, settings.fps, dpi, isNight(), settings.rightHandDrive, settings.wifi5g,
            settings.dongleMic, settings.bluetoothAudio, settings.mediaDelay, settings.dongleName)
        val old = config
        config = cfg
        if (old == null || old == cfg) return
        if (old.copy(night = cfg.night) == cfg) dongle?.setNight(cfg.night) else reconnect()
    }

    private fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequest < 500) return
        lastKeyframeRequest = now
        dongle?.command(DongleProtocol.CMD_FRAME)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (dongle == null) tryConnect()
            main.postDelayed(this, 1000)
        }
    }

    private fun tryConnect() {
        if (connecting) return
        val cfg = config ?: return
        val open: () -> DongleTransport
        val bridge = settings.debugBridge
        if (bridge) {
            val host = settings.bridgeHost
            open = { DongleTcpTransport(host, BRIDGE_PORT) }
        } else {
            val dev = findDongle(usb) ?: run {
                permissionAsked = null
                setStatus(getString(R.string.dongle_plug_in), false, waitingOnDriver = true); return
            }
            if (!usb.hasPermission(dev)) {
                if (permissionAsked?.deviceName != dev.deviceName) {
                    permissionAsked = dev
                    val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
                    UsbSelfGrant.requestPermission(this, usb, dev, pi)
                }
                setStatus(getString(R.string.dongle_allow_access), false, waitingOnDriver = true); return
            }
            open = { DongleUsbTransport(usb, dev) }
        }
        // Opening a socket (and the handshake writes) must stay off the main thread.
        connecting = true
        connector.execute {
            val a = DongleAudio { pcm, n -> dongle?.send(DongleProtocol.AUDIO, DongleProtocol.micAudio(pcm, n)) }
            try {
                val d = DongleSession(open(), ::onMessage) { main.post { onDongleClosed(a) } }
                dongle = d; audio = a; dongleAnswered = false
                d.start(cfg)
                lastConnectError = null
                AppLog.i("[$TAG] connected${if (bridge) " via bridge" else ""} ${cfg.width}x${cfg.height}@${cfg.fps} dpi=${cfg.dpi}")
            } catch (e: Exception) {
                // The poll retries every second; one line per new failure is enough for the trail.
                if (e.toString() != lastConnectError) AppLog.w("[$TAG] connect: $e")
                lastConnectError = e.toString()
                dongle = null; audio = null; a.release()
                setStatus(if (bridge) getString(R.string.dongle_bridge_unreachable, "${settings.bridgeHost}:$BRIDGE_PORT")
                    else getString(R.string.dongle_usb_error, e.message.orEmpty()), false)
            } finally {
                connecting = false
            }
        }
    }

    private fun onDongleClosed(a: DongleAudio) {
        a.release()
        if (audio === a) { audio = null; dongle = null }
        setStatus(getString(R.string.dongle_reconnecting), false)
    }

    private fun onMessage(type: Int, p: ByteArray) {
        if (!dongleAnswered) {
            dongleAnswered = true
            if (type != DongleProtocol.PLUGGED) setStatus(getString(R.string.dongle_waiting_phone), false)
        }
        when (type) {
            DongleProtocol.VIDEO -> video.feed(p)
            DongleProtocol.AUDIO -> audio?.onPacket(p)
            DongleProtocol.PLUGGED -> {
                val kind = if (p.size >= 4) DongleVideo.le(p, 0) else 0
                val name = when (kind) { 3 -> "CarPlay"; 5 -> "Android Auto"; 6 -> "HiCar"; else -> getString(R.string.dongle_phone_generic) }
                setStatus(getString(R.string.dongle_phone_connected, name), true)
                requestKeyframe()
            }
            DongleProtocol.UNPLUGGED -> setStatus(getString(R.string.dongle_phone_lost), false)
            DongleProtocol.COMMAND -> if (p.size >= 4) {
                val text = DongleProtocol.commandText(DongleVideo.le(p, 0))
                if (text != 0 && !phoneConnected) setStatus(getString(text), false)
            }
            DongleProtocol.MEDIA_DATA -> onMediaData(p)
            DongleProtocol.SOFTWARE_VERSION -> putInfo(R.string.dongle_info_firmware, text(p))
            DongleProtocol.BT_NAME -> putInfo(R.string.dongle_info_bt_name, text(p))
            DongleProtocol.WIFI_NAME -> putInfo(R.string.dongle_info_wifi_name, text(p))
            DongleProtocol.PAIRED_LIST -> putInfo(R.string.dongle_info_paired,
                text(p).lines().filter { it.length > 17 }.joinToString(", ") { it.substring(17) })
            DongleProtocol.BOX_SETTINGS -> onBoxInfo(p)
        }
    }

    // The dongle sends partial updates (often just MediaSongPlayTime), so merge them into what we have.
    private val metadata = MediaMetadata.Builder()

    private fun text(p: ByteArray) = String(p).trimEnd('\u0000', '\n').trim()

    private fun putInfo(key: Int, value: String) {
        if (value.isEmpty()) return
        val name = getString(key)
        AppLog.i("[$TAG] $name: $value")
        main.post { info[name] = value }
    }

    private fun onBoxInfo(p: ByteArray) {
        val j = try { JSONObject(text(p)) } catch (_: Exception) { return }
        putInfo(R.string.dongle_info_model, j.optString("productType"))
        putInfo(R.string.dongle_info_hw, j.optString("hwVersion"))
        putInfo(R.string.dongle_info_support, j.optString("supportLinkType"))
        if (j.has("WiFiChannel")) putInfo(R.string.dongle_info_channel, j.optInt("WiFiChannel").toString())
        putInfo(R.string.dongle_info_phone, j.optString("MDModel"))
        putInfo(R.string.dongle_info_mode, j.optString("MDLinkType"))
        if (j.has("cpuTemp")) putInfo(R.string.dongle_info_temp, "${j.optInt("cpuTemp")} °C")
    }

    private fun onMediaData(p: ByteArray) {
        if (p.size < 5) return
        when (DongleVideo.le(p, 0)) {
            1 -> {
                val j = try { JSONObject(String(p, 4, p.size - 4).trimEnd('\u0000')) } catch (_: Exception) { return }
                if (!j.has("MediaSongName") && !j.has("MediaArtistName") && !j.has("MediaAlbumName")) return
                main.post {
                    j.optString("MediaSongName").let { metadata.putString(MediaMetadata.METADATA_KEY_TITLE, it) }
                    j.optString("MediaArtistName").let { metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
                    j.optString("MediaAlbumName").let { metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                    if (j.has("MediaSongDuration")) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, j.optLong("MediaSongDuration"))
                    session.setMetadata(metadata.build())
                }
            }
            2 -> {
                val art = BitmapFactory.decodeByteArray(p, 4, p.size - 4) ?: return
                main.post {
                    metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    session.setMetadata(metadata.build())
                }
            }
        }
    }

    private fun setStatus(text: String, connected: Boolean, waitingOnDriver: Boolean = false) {
        main.post {
            if (text == status && connected == phoneConnected) return@post
            status = text; phoneConnected = connected
            mutableState.value = State(active = true, status = text, phoneConnected = connected, waitingOnDriver = waitingOnDriver)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev != null && isDongle(dev)) dongle?.close()
                }
                else -> main.post { if (dongle == null) tryConnect() }
            }
        }
    }

    private fun setupMediaSession() {
        session = MediaSession(this, "Dongle")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { command(DongleProtocol.CMD_PLAY) }
            override fun onPause() { command(DongleProtocol.CMD_PAUSE) }
            override fun onSkipToNext() { command(DongleProtocol.CMD_NEXT) }
            override fun onSkipToPrevious() { command(DongleProtocol.CMD_PREV) }
            override fun onStop() { command(DongleProtocol.CMD_PAUSE) }
        })
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        session.isActive = true
    }

    private fun startForegroundNotification() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, DongleActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DongleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setContentTitle(getString(R.string.dongle_title))
            .setContentText(getString(R.string.dongle_notification))
            .setContentIntent(open)
            .addAction(0, getString(R.string.dongle_turn_off), stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            // Claiming the microphone without the permission throws on Android 14+.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, n, type)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val TAG = "Dongle"
        const val VENDOR = 0x1314
        val PRODUCTS = setOf(0x1520, 0x1521)
        const val ACTION_PERMISSION = "com.lenid.zoh.dongle.USB_PERMISSION"
        const val ACTION_STOP = "com.lenid.zoh.dongle.STOP"
        const val ACTION_RECONNECT = "com.lenid.zoh.dongle.RECONNECT"
        const val BRIDGE_PORT = 5277
        private const val NOTIFICATION_ID = 7

        private val mutableState = MutableStateFlow(State())
        /** The session as the home tile and the dongle tab show it. */
        val state: StateFlow<State> = mutableState

        /** What the dongle told us about itself and the phone, shown in the dongle tab. Main thread only. */
        val info = LinkedHashMap<String, String>()

        fun isDongle(d: UsbDevice) = d.vendorId == VENDOR && d.productId in PRODUCTS

        fun findDongle(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull(::isDongle)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, DongleService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, DongleService::class.java))
        }

        fun sendReconnect(context: Context) {
            if (!state.value.active) return
            context.startService(Intent(context, DongleService::class.java).setAction(ACTION_RECONNECT))
        }
    }
}
