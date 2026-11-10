package com.lenid.zoh.aap

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import androidx.core.content.FileProvider
import com.lenid.zoh.main.MainActivity
import com.lenid.zoh.utils.AppLog
import java.io.File

/**
 * Puts the Android Auto track on the Zeekr home screen music widget (MediaWidget in
 * ecarx.launcher3), with its play/pause, next and previous buttons, as a source of its own
 * ("Open Zeekr Headunit" in the widget's source list).
 *
 * The widget is fed by the system media center (com.zeekr.mediacenter), which has no public SDK
 * here, so its binder interfaces are spoken by hand. Transaction codes and parcel layouts were
 * read from the media center APK and checked on the car:
 *  - bind `ecarx.xsf.ZEEKR_MEDIA_CENTER_SERVICE`; asyncBinderCall (3) of the support service
 *    hands out IMediaCenterSvc and IMediaPartClientApiSvc through the callbacks.
 *  - registerInMusic makes us a music client; registerZeekrInMusic plus updateStartUp put us in
 *    the widget's source list (it keeps only apps whose versionCode is above 1).
 *  - updateMediaSourceTypeList is what lets the widget switch to us (sourceSelected).
 *  - Next and previous reach [client] directly. Play and pause do not while no stock player
 *    plays: the media center then raises Settings.Global `handle_pause_action` for whoever
 *    takes over the controls (GMC did, once), so that flag is watched as well, together with
 *    `handle_next_action` and `handle_prev_action` from the steering wheel.
 *
 * Android Auto sends no queue, likes or repeat mode, so the widget's playlist stays empty.
 */
internal class MediaCenterBridge(
    private val context: Context,
    /** Sends a media key (down and up) to the phone; called on the main thread. */
    private val sendKey: (Int) -> Unit,
) {
    private val thread = HandlerThread("MediaCenterBridge").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var bound = false
    private var support: IBinder? = null
    private var svc: IBinder? = null
    private var partSvc: IBinder? = null
    private var token: IBinder? = null
    private var partToken: IBinder? = null
    private var startUpSent = false
    private var focusRequested = false

    @Volatile private var title = ""
    @Volatile private var artist = ""
    @Volatile private var album = ""
    @Volatile private var durationMs = 0L
    @Volatile private var positionMs = 0L
    @Volatile private var playing = false
    @Volatile private var artUri: Uri? = null
    private var artKey: Int? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            worker.post {
                support = service
                asyncBinderCall(METHOD_MUSIC, musicParam(), svcCallback)
                asyncBinderCall(METHOD_PART, ByteArray(0), partCallback)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The media center restarted: every token it gave is gone. The binding stays and
            // reconnects by itself, and onServiceConnected registers again.
            worker.post { forget() }
        }
    }

    fun start() {
        if (bound) return
        val intent = Intent(SERVICE_ACTION).setPackage(MEDIA_CENTER)
        bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            AppLog.w("MediaCenterBridge: bind failed: ${e.message}")
            false
        }
        if (bound) {
            // A flag left at 1 (nobody handled it) would never change again: writing the same
            // value does not notify the observer. This bridge only runs in DIRECT mode, so it
            // owns these flags even when MyRegion/GMC is installed.
            for (key in FLAGS) {
                try { android.provider.Settings.Global.putInt(context.contentResolver, key, 0) } catch (_: Exception) {}
            }
            clusterGate(true)
        }
        if (bound) {
            for (key in FLAGS) {
                context.contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor(key), false, flagObserver)
            }
        }
    }

    fun stop() {
        if (!bound) return
        bound = false
        context.contentResolver.unregisterContentObserver(flagObserver)
        worker.post {
            token?.let { t -> call(svc, SVC, TX_UNREGISTER) { it.writeStrongBinder(t) } }
            clusterGate(false)
            forget()
            title = ""; artist = ""; album = ""; durationMs = 0L; positionMs = 0L; playing = false
            artUri = null; artKey = null
            File(context.cacheDir, ART_DIR).listFiles()?.forEach { it.delete() }
        }
        try { context.unbindService(connection) } catch (_: Exception) {}
    }

    /** [art] and [artKey] as for [RouteMapTrackBridge.update]. Main thread. */
    fun track(song: String, artist: String, album: String, durationMs: Long, art: Bitmap?, artKey: Int?) {
        if (!bound) return
        AppLog.i("MediaCenterBridge: track '$song' / '$artist', art=${art != null}")
        this.title = song
        this.artist = artist
        this.album = album
        this.durationMs = durationMs
        if (art == null) {
            if (artKey == null) { artUri = null; this.artKey = null }
            worker.post { push() }
            return
        }
        if (artKey == this.artKey && artUri != null) { worker.post { push() }; return }
        this.artKey = artKey
        worker.post {
            artUri = saveArt(art, artKey ?: art.hashCode())
            push()
        }
    }

    /** Status arrives about once a second from the phone. Main thread. */
    fun playState(playing: Boolean, positionMs: Long) {
        if (!bound) return
        val changed = playing != this.playing
        this.playing = playing
        this.positionMs = positionMs
        worker.post {
            if (changed) {
                AppLog.i("MediaCenterBridge: playState playing=$playing")
                push()
            }
            call(svc, SVC, TX_UPDATE_PROGRESS) { d -> d.writeStrongBinder(token ?: return@call false); d.writeLong(positionMs) }
        }
    }

    private fun forget() {
        support = null; svc = null; partSvc = null; token = null; partToken = null
        startUpSent = false; focusRequested = false
    }

    // ---- outgoing calls (worker thread) ----

    private fun asyncBinderCall(method: String, param: ByteArray, callback: Binder) {
        call(support, SUPPORT, TX_ASYNC_BINDER_CALL) { d ->
            d.writeInt(1)
            d.writeString(SDK_SERVICE); d.writeString(SDK_SERVICE); d.writeString(method)
            d.writeByteArray(param); d.writeByteArray(ByteArray(0))
            d.writeStrongBinder(callback)
        }
    }

    /** RequestMediaSvcPois as its CREATOR reads it: no Parcelable, an empty list. */
    private fun musicParam(): ByteArray {
        val p = Parcel.obtain()
        return try { p.writeString(null); p.writeInt(0); p.marshall() } finally { p.recycle() }
    }

    private fun onSvc(binder: IBinder) {
        svc = binder
        val pkg = context.packageName
        token = callForBinder(binder, SVC, TX_REGISTER_IN_MUSIC) { it.writeString(pkg); it.writeStrongBinder(client) }
        partToken = callForBinder(binder, SVC, TX_REGISTER_ZEEKR_IN_MUSIC) { it.writeString(pkg); it.writeStrongBinder(client) }
        val t = token ?: run { AppLog.w("MediaCenterBridge: no client token"); return }
        call(binder, SVC, TX_UPDATE_SOURCE_TYPE_LIST) { it.writeStrongBinder(t); it.writeIntArray(intArrayOf(SOURCE_TYPE_ANDROID_AUTO)) }
        call(binder, SVC, TX_UPDATE_SOURCE_TYPE) { it.writeStrongBinder(t); it.writeInt(SOURCE_TYPE_ANDROID_AUTO) }
        AppLog.i("MediaCenterBridge: registered with the media center")
        sendStartUp()
        push()
    }

    private fun onPartSvc(binder: IBinder) {
        partSvc = binder
        sendStartUp()
    }

    /** What the widget opens when its card is tapped; also what puts us in its source list. */
    private fun sendStartUp() {
        if (startUpSent) return
        val part = partSvc ?: return
        val t = partToken ?: return
        startUpSent = call(part, PART, TX_UPDATE_START_UP) { d ->
            d.writeStrongBinder(t)
            d.writeInt(1); openApp().writeToParcel(d, 0)
            d.writeInt(1) // canHalf
        }
    }

    /** Shows the playback info to the widget, taking its focus when the phone starts playing. */
    private fun push() {
        val s = svc ?: return
        val t = token ?: return
        if (playing && !focusRequested) {
            focusRequested = call(s, SVC, TX_REQUEST_PLAY) { it.writeStrongBinder(t) }
        }
        // Another player (Bluetooth, QQ Music, radio) changes the media center's current source
        // type. Updates with our type are rejected until it is restored, so do it before every
        // metadata/status push rather than only once during registration.
        clusterGate(true)
        call(s, SVC, TX_UPDATE_SOURCE_TYPE) { it.writeStrongBinder(t); it.writeInt(SOURCE_TYPE_ANDROID_AUTO) }
        call(s, SVC, TX_UPDATE_PLAYBACK) { it.writeStrongBinder(t); it.writeStrongBinder(info) }
    }

    private var clusterGateOwned = false

    /**
     * Zeekr's DimInteractionHelper sends artwork to the instrument cluster unconditionally, but
     * title, artist, playback state and progress only while media_is_playing (or radio_is_playing)
     * is 1. DIRECT mode replaces the mod which used to raise this flag. Keep it up while this
     * bridge owns the source, including paused playback, so the pause itself reaches the cluster.
     */
    private fun clusterGate(enable: Boolean) {
        try {
            val resolver = context.contentResolver
            val key = "media_is_playing"
            if (enable) {
                if (android.provider.Settings.Global.getInt(resolver, key, 0) != 1) {
                    android.provider.Settings.Global.putInt(resolver, key, 1)
                }
                clusterGateOwned = true
            } else if (clusterGateOwned) {
                clusterGateOwned = false
                if (android.provider.Settings.Global.getInt(resolver, key, 0) != 0) {
                    android.provider.Settings.Global.putInt(resolver, key, 0)
                }
            }
        } catch (e: Exception) {
            AppLog.w("MediaCenterBridge: cluster media gate failed: ${e.message}")
        }
    }

    private fun call(binder: IBinder?, descriptor: String, code: Int, write: (Parcel) -> Any?): Boolean {
        binder ?: return false
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(descriptor)
            if (write(d) == false) return false
            binder.transact(code, d, r, 0)
            r.readException()
            true
        } catch (e: Exception) {
            AppLog.w("MediaCenterBridge: call $code failed: ${e.message}")
            false
        } finally {
            d.recycle(); r.recycle()
        }
    }

    private fun callForBinder(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit): IBinder? {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(descriptor)
            write(d)
            binder.transact(code, d, r, 0)
            r.readException()
            r.readStrongBinder()
        } catch (e: Exception) {
            AppLog.w("MediaCenterBridge: call $code failed: ${e.message}")
            null
        } finally {
            d.recycle(); r.recycle()
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The cover as a file the widget (another process) may read, one file per cover. */
    private fun saveArt(art: Bitmap, key: Int): Uri? = try {
        val dir = File(context.cacheDir, ART_DIR).apply { mkdirs() }
        val name = "cover_${key.toUInt()}.jpg"
        dir.listFiles()?.filter { it.name != name }?.forEach { it.delete() }
        val file = File(dir, name)
        if (!file.exists()) file.outputStream().use { art.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).also { uri ->
            for (pkg in ART_READERS) {
                try { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
        }
    } catch (e: Exception) {
        AppLog.w("MediaCenterBridge: cover not saved: ${e.message}")
        null
    }

    // ---- incoming calls (binder threads) ----

    private fun key(code: Int) = main.post { if (bound) sendKey(code) }

    private val svcCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(SVC_CALLBACK); return true }
            when (code) {
                1 -> { data.enforceInterface(SVC_CALLBACK); reply?.writeNoException(); reply?.writeStrongBinder(null) }
                2 -> {
                    data.enforceInterface(SVC_CALLBACK)
                    val binder = data.readStrongBinder()
                    reply?.writeNoException()
                    if (binder != null) worker.post { if (bound) onSvc(binder) }
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private val partCallback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(PART_CALLBACK); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(PART_CALLBACK)
            val binder = data.readStrongBinder()
            reply?.writeNoException()
            if (binder != null) worker.post { if (bound) onPartSvc(binder) }
            return true
        }
    }

    /** IZeekrMusicClient: what the media center asks of us and the buttons it forwards. */
    private val client = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CLIENT); return true }
            if (code !in 1..47) return super.onTransact(code, data, reply, flags)
            val r = reply ?: Parcel.obtain()
            r.writeNoException()
            when (code) {
                1, 41 -> { key(KeyEvent.KEYCODE_MEDIA_PLAY); r.writeInt(1) }
                2, 42 -> { key(KeyEvent.KEYCODE_MEDIA_PAUSE); r.writeInt(1) }
                3, 43 -> { key(KeyEvent.KEYCODE_MEDIA_NEXT); r.writeInt(1) }
                4, 44 -> { key(KeyEvent.KEYCODE_MEDIA_PREVIOUS); r.writeInt(1) }
                // Picked in the widget's source list: explicitly retake its focus, then play as
                // the stock players do. A previous request may have succeeded before another
                // source was selected, so focusRequested must not suppress this explicit request.
                8 -> {
                    AppLog.i("MediaCenterBridge: widget selected us as the source, playing=$playing")
                    worker.post {
                        focusRequested = call(svc, SVC, TX_REQUEST_PLAY) { d ->
                            d.writeStrongBinder(token ?: return@call false)
                        }
                        push()
                    }
                    if (!playing) key(KeyEvent.KEYCODE_MEDIA_PLAY)
                    r.writeInt(1)
                }
                10 -> r.writeStrongBinder(info)
                11 -> r.writeIntArray(intArrayOf(SOURCE_TYPE_ANDROID_AUTO))
                12 -> r.writeInt(SOURCE_TYPE_ANDROID_AUTO)
                13 -> r.writeLong(positionMs)
                14, 28 -> r.writeInt(-1) // no list
                17 -> r.writeInt(1) // onSourceChanged
                25, 26, 33, 35 -> if (code == 26) r.writeInt(1) // void, or onExit
                else -> r.writeInt(0) // not supported (likes, downloads, repeat, lists...) or null
            }
            if (reply == null) r.recycle()
            return true
        }
    }

    /** IMusicPlaybackInfo: the track as the widget reads it, field by field. */
    private val info = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(INFO); return true }
            if (code !in 1..42 || reply == null) return super.onTransact(code, data, reply, flags)
            reply.writeNoException()
            when (code) {
                1, 33, 39, 40 -> { reply.writeInt(1); openApp().writeToParcel(reply, 0) }
                2 -> reply.writeString(title)
                3 -> reply.writeString(artist)
                4 -> reply.writeString(album)
                7 -> reply.writeLong(durationMs)
                9 -> reply.writeInt(SOURCE_TYPE_ANDROID_AUTO)
                11 -> reply.writeInt(if (playing) 1 else 0)
                16 -> {
                    val uri = artUri
                    if (uri == null) reply.writeInt(0) else { reply.writeInt(1); uri.writeToParcel(reply, 0) }
                }
                25 -> reply.writeString(context.applicationInfo.loadLabel(context.packageManager).toString())
                27 -> reply.writeString(context.packageName)
                41 -> reply.writeInt(0)
                42 -> reply.writeLong(positionMs)
                5, 6, 12, 14, 24, 26, 30, 35, 37, 38 -> reply.writeString(null)
                else -> reply.writeInt(0) // Uris without a value, counters, unsupported flags
            }
            return true
        }
    }

    /**
     * The hand-off flags the media center raises when no stock player plays. A pause flag is a
     * toggle (the widget's play and pause buttons both raise it). Each is cleared once handled,
     * so the next press raises it again. This observer exists only in DIRECT mode; the bridge is
     * not running at all when the user chooses GMC as the widget target.
     */
    private val flagObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (!bound) return
            val resolver = context.contentResolver
            for (flag in FLAGS) {
                if (android.provider.Settings.Global.getInt(resolver, flag, 0) != 1) continue
                try { android.provider.Settings.Global.putInt(resolver, flag, 0) } catch (_: Exception) {}
                when (flag) {
                    FLAG_PAUSE -> sendKey(if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY)
                    FLAG_NEXT -> sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                    FLAG_PREV -> sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
        }
    }

    companion object {
        const val MEDIA_CENTER = "com.zeekr.mediacenter"
        private const val SERVICE_ACTION = "ecarx.xsf.ZEEKR_MEDIA_CENTER_SERVICE"

        private const val SUPPORT = "com.zeekr.sdk.base.internal.IZeekrSupportService"
        private const val SVC = "com.zeekr.sdk.mediacenter.IMediaCenterSvc"
        private const val SVC_CALLBACK = "com.zeekr.sdk.mediacenter.IMediaCenterSvcCallBack"
        private const val PART = "com.zeekr.sdk.mediacenter.mediapart.IMediaPartClientApiSvc"
        private const val PART_CALLBACK = "com.zeekr.sdk.mediacenter.IMediaPartClientSvcCallBack"
        private const val CLIENT = "com.zeekr.sdk.mediacenter.IZeekrMusicClient"
        private const val INFO = "com.zeekr.sdk.mediacenter.IMusicPlaybackInfo"

        private const val SDK_SERVICE = "ZeekrMediaCenterAPI"
        private const val METHOD_MUSIC = "mediaCenterUpdateMusicPlayBackState"
        private const val METHOD_PART = "mediaCenterUpdateMediaPart"

        private const val TX_ASYNC_BINDER_CALL = 3
        private const val TX_UNREGISTER = 5
        private const val TX_REQUEST_PLAY = 6
        private const val TX_UPDATE_PLAYBACK = 7
        private const val TX_UPDATE_SOURCE_TYPE_LIST = 8
        private const val TX_UPDATE_SOURCE_TYPE = 9
        private const val TX_UPDATE_PROGRESS = 11
        private const val TX_REGISTER_IN_MUSIC = 20
        private const val TX_REGISTER_ZEEKR_IN_MUSIC = 21
        private const val TX_UPDATE_START_UP = 3

        private const val SOURCE_TYPE_ANDROID_AUTO = 39

        private const val FLAG_PAUSE = "handle_pause_action"
        private const val FLAG_NEXT = "handle_next_action"
        private const val FLAG_PREV = "handle_prev_action"
        private val FLAGS = listOf(FLAG_PAUSE, FLAG_NEXT, FLAG_PREV)

        private const val ART_DIR = "media_center"
        /** The widget loads the cover itself; the media center may hand the Uri on. */
        private val ART_READERS = listOf("ecarx.launcher3", MEDIA_CENTER)

        fun isAvailable(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(MEDIA_CENTER, 0).enabled
        } catch (_: Exception) { false }
    }
}
