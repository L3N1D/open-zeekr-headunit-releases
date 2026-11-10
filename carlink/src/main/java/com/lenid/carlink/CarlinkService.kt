package com.lenid.carlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.json.JSONObject
import java.util.concurrent.Executors

class CarlinkService : Service() {
    interface Listener {
        fun onStatus(text: String, phoneConnected: Boolean)
        fun onFrameSize(width: Int, height: Int)
    }

    inner class LocalBinder : Binder() { val service get() = this@CarlinkService }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var usb: UsbManager
    private lateinit var session: MediaSession
    @Volatile private var dongle: Dongle? = null
    @Volatile private var audio: Audio? = null
    @Volatile private var connecting = false
    /** False until the dongle answers; a TCP connect alone does not mean the bridge reached it. */
    @Volatile private var dongleAnswered = false
    private val connector = Executors.newSingleThreadExecutor()
    private lateinit var settings: Settings
    private val video = VideoDecoder({ requestKeyframe() }) { w, h ->
        main.post { frameW = w; frameH = h; listener?.onFrameSize(w, h) }
    }
    var frameW = 0
        private set
    var frameH = 0
        private set
    private var lastKeyframeRequest = 0L
    private var permissionAsked: UsbDevice? = null

    var config: Config? = null

    /** What the dongle told us about itself and the phone, shown in settings. Main thread only. */
    val info = LinkedHashMap<String, String>()
    var listener: Listener? = null
        set(v) { field = v; v?.onStatus(status, phoneConnected) }
    private var status = "Подключите адаптер Carlinkit"
    private var phoneConnected = false

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(USB_SERVICE) as UsbManager
        settings = Settings(this)
        startForegroundNotification()
        setupMediaSession()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        registerReceiver(usbReceiver, f)
        main.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        dongle?.close()
        video.release()
        session.release()
        super.onDestroy()
    }

    fun setSurface(s: Surface?) = video.setSurface(s)

    fun hasVideo() = video.hasFrames

    fun touch(action: Int, x: Float, y: Float) = dongle?.send(Protocol.TOUCH, Protocol.touch(action, x, y))

    fun command(c: Int) = dongle?.command(c)

    fun setNight(night: Boolean) { dongle?.setNight(night) }

    /** Re-run the handshake, e.g. after the screen size changed. */
    fun reconnect() { dongle?.close() }

    private fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyframeRequest < 500) return
        lastKeyframeRequest = now
        dongle?.command(Protocol.CMD_FRAME)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (dongle == null) tryConnect()
            main.postDelayed(this, 1000)
        }
    }

    private fun findDevice(): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == VENDOR && it.productId in PRODUCTS }

    private fun tryConnect() {
        if (connecting) return
        val cfg = config ?: return
        val open: () -> Transport
        val bridge = settings.debugBridge
        if (bridge) {
            val host = settings.bridgeHost
            open = { TcpTransport(host, BRIDGE_PORT) }
        } else {
            val dev = findDevice() ?: run {
                permissionAsked = null
                setStatus("Подключите адаптер Carlinkit", false); return
            }
            if (!usb.hasPermission(dev)) {
                if (permissionAsked?.deviceName != dev.deviceName) {
                    permissionAsked = dev
                    val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                        PendingIntent.FLAG_IMMUTABLE)
                    usb.requestPermission(dev, pi)
                }
                setStatus("Разрешите доступ к адаптеру", false); return
            }
            open = { UsbTransport(usb, dev) }
        }
        // Opening a socket (and the handshake writes) must stay off the main thread.
        connecting = true
        connector.execute {
            val a = Audio { pcm, n -> dongle?.send(Protocol.AUDIO, Protocol.micAudio(pcm, n)) }
            try {
                val d = Dongle(open(), ::onMessage) { main.post { onDongleClosed(a) } }
                dongle = d; audio = a; dongleAnswered = false
                d.start(cfg)
                Log.i(TAG, "connected${if (bridge) " via bridge" else ""} ${cfg.width}x${cfg.height}@${cfg.fps} dpi=${cfg.dpi}")
            } catch (e: Exception) {
                Log.w(TAG, "connect: $e")
                dongle = null; audio = null; a.release()
                setStatus(if (bridge) "Мост недоступен: ${settings.bridgeHost}:$BRIDGE_PORT" else "Ошибка USB: ${e.message}", false)
            } finally {
                connecting = false
            }
        }
    }

    private fun onDongleClosed(a: Audio) {
        a.release()
        if (audio === a) { audio = null; dongle = null }
        setStatus("Адаптер переподключается…", false)
    }

    private fun onMessage(type: Int, p: ByteArray) {
        if (!dongleAnswered) {
            dongleAnswered = true
            if (type != Protocol.PLUGGED) setStatus("Адаптер подключён, ожидание телефона…", false)
        }
        when (type) {
            Protocol.VIDEO -> video.feed(p)
            Protocol.AUDIO -> audio?.onPacket(p)
            Protocol.PLUGGED -> {
                val kind = if (p.size >= 4) VideoDecoder.le(p, 0) else 0
                val name = when (kind) { 3 -> "CarPlay"; 5 -> "Android Auto"; 6 -> "HiCar"; else -> "телефон" }
                setStatus("$name подключён", true)
                requestKeyframe()
            }
            Protocol.UNPLUGGED -> setStatus("Телефон отключён, ожидание…", false)
            Protocol.COMMAND -> if (p.size >= 4) {
                val text = Protocol.commandName(VideoDecoder.le(p, 0))
                if (text.isNotEmpty() && !phoneConnected) setStatus(text, false)
            }
            Protocol.MEDIA_DATA -> onMediaData(p)
            Protocol.SOFTWARE_VERSION -> putInfo("Прошивка", text(p))
            Protocol.BT_NAME -> putInfo("Имя Bluetooth", text(p))
            Protocol.WIFI_NAME -> putInfo("Имя Wi-Fi", text(p))
            Protocol.PAIRED_LIST -> putInfo("Сопряжённые телефоны",
                text(p).lines().filter { it.length > 17 }.joinToString(", ") { it.substring(17) })
            Protocol.BOX_SETTINGS -> onBoxInfo(p)
        }
    }

    // The dongle sends partial updates (often just MediaSongPlayTime), so merge them into what we have.
    private val metadata = MediaMetadata.Builder()

    private fun text(p: ByteArray) = String(p).trimEnd('\u0000', '\n').trim()

    private fun putInfo(key: String, value: String) {
        if (value.isEmpty()) return
        Log.i(TAG, "$key: $value")
        main.post { info[key] = value }
    }

    private fun onBoxInfo(p: ByteArray) {
        val j = try { JSONObject(text(p)) } catch (_: Exception) { return }
        j.optString("productType").let { putInfo("Модель донгла", it) }
        j.optString("hwVersion").let { putInfo("Аппаратная версия", it) }
        j.optString("supportLinkType").let { putInfo("Поддержка", it) }
        if (j.has("WiFiChannel")) putInfo("Канал Wi-Fi", j.optInt("WiFiChannel").toString())
        j.optString("MDModel").let { putInfo("Телефон", it) }
        j.optString("MDLinkType").let { putInfo("Режим", it) }
        if (j.has("cpuTemp")) putInfo("Температура донгла", "${j.optInt("cpuTemp")} °C")
    }

    private fun onMediaData(p: ByteArray) {
        if (p.size < 5) return
        when (VideoDecoder.le(p, 0)) {
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

    private fun setStatus(text: String, connected: Boolean) {
        main.post {
            if (text == status && connected == phoneConnected) return@post
            status = text; phoneConnected = connected
            listener?.onStatus(text, connected)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (dev?.vendorId == VENDOR) dongle?.close()
                }
                else -> main.post { if (dongle == null) tryConnect() }
            }
        }
    }

    private fun setupMediaSession() {
        session = MediaSession(this, "Carlink")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { command(Protocol.CMD_PLAY) }
            override fun onPause() { command(Protocol.CMD_PAUSE) }
            override fun onSkipToNext() { command(Protocol.CMD_NEXT) }
            override fun onSkipToPrevious() { command(Protocol.CMD_PREV) }
            override fun onStop() { command(Protocol.CMD_PAUSE) }
        })
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
        session.isActive = true
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("carlink", "Carlink", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val openSettings = PendingIntent.getActivity(this, 1, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "carlink")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Carlink")
            .setContentText("CarPlay / Android Auto")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification), getString(R.string.settings), openSettings).build())
            .build()
        startForeground(1, n)
    }

    companion object {
        const val TAG = "Carlink"
        const val VENDOR = 0x1314
        val PRODUCTS = setOf(0x1520, 0x1521)
        const val ACTION_PERMISSION = "com.lenid.carlink.USB_PERMISSION"
        const val BRIDGE_PORT = 5277
    }
}
