package com.lenid.carlink

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback, CarlinkService.Listener {
    private var service: CarlinkService? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private lateinit var root: FrameLayout
    private lateinit var overlay: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var settingsSmall: View
    private lateinit var settings: Settings
    private var screenW = 0
    private var screenH = 0
    private var surfaceReady = false
    private val touchSender = Executors.newSingleThreadExecutor()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as CarlinkService.LocalBinder).service
            service = s
            s.listener = this@MainActivity
            applyConfig()
            layoutVideo()
            if (surfaceReady) s.setSurface(surfaceView.holder.surface)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        surfaceView = findViewById(R.id.video)
        overlay = findViewById(R.id.overlay)
        statusView = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        settingsSmall = findViewById(R.id.settings_small)
        for (id in intArrayOf(R.id.settings, R.id.settings_small)) {
            findViewById<View>(id).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        surfaceView.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) { screenW = r - l; screenH = b - t; applyConfig(); layoutVideo() }
        }
        surfaceView.setOnTouchListener { v, e -> onTouch(v, e) }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        val i = Intent(this, CarlinkService::class.java)
        startForegroundService(i)
        bindService(i, conn, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        // The AA / CarPlay side rail moves to the right for right-hand drive; keep the button on it.
        (settingsSmall.layoutParams as FrameLayout.LayoutParams).gravity =
            Gravity.TOP or if (settings.rightHandDrive) Gravity.END else Gravity.START
        settingsSmall.requestLayout()
        applyConfig() // settings may have changed
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onDestroy() {
        service?.listener = null
        service?.setSurface(null)
        unbindService(conn)
        super.onDestroy()
    }

    // Keep the session (and audio) alive; just leave the screen.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { moveTaskToBack(true) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyConfig()
    }

    private fun isNight() = when (settings.nightMode) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (surfaceReady) return // only a resize from layoutVideo(); the decoder keeps its surface
        surfaceReady = true
        service?.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        service?.setSurface(null)
    }

    /**
     * Build the dongle config from the settings. By default ask for a stream matching the screen
     * (max 1920 wide; the surface scales it up). A changed night mode is sent live, anything else
     * needs a new handshake.
     */
    private fun applyConfig() {
        val s = service ?: return
        if (screenW == 0 || screenH == 0) return
        val scale = minOf(1f, 1920f / screenW, 1080f / screenH)
        val fixed = settings.resolution.split("x").mapNotNull { it.toIntOrNull() }
        val (w, h) = if (fixed.size == 2) fixed[0] to fixed[1]
            else ((screenW * scale).toInt() / 8) * 8 to ((screenH * scale).toInt() / 8) * 8
        val dpi = if (settings.dpi > 0) settings.dpi
            else (resources.displayMetrics.densityDpi * scale).toInt().coerceIn(120, 320)
        val cfg = Config(w, h, settings.fps, dpi, isNight(), settings.rightHandDrive, settings.wifi5g,
            settings.dongleMic, settings.bluetoothAudio, settings.mediaDelay, settings.dongleName)
        val old = s.config
        s.config = cfg
        if (old == null || old == cfg) return
        if (old.copy(night = cfg.night) == cfg) s.setNight(cfg.night) else s.reconnect()
    }

    /**
     * Android Auto only streams standard sizes (e.g. 1920x1080) and letterboxes the requested
     * area inside. Scale the frame to cover the screen so the bars fall outside of it; touch
     * coordinates stay relative to the full frame, which is what the dongle expects.
     */
    private fun layoutVideo() {
        val s = service ?: return
        val fw = s.frameW; val fh = s.frameH
        if (screenW == 0 || screenH == 0) return
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        if (fw == 0 || fh == 0) { lp.width = -1; lp.height = -1 } else {
            val cover = maxOf(screenW.toFloat() / fw, screenH.toFloat() / fh)
            lp.width = Math.round(fw * cover); lp.height = Math.round(fh * cover)
        }
        surfaceView.layoutParams = lp
    }

    override fun onFrameSize(width: Int, height: Int) = layoutVideo()

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val s = service ?: return false
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> Protocol.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> Protocol.TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Protocol.TOUCH_UP
            else -> return true
        }
        val x = e.x / v.width
        val y = e.y / v.height
        touchSender.execute { s.touch(action, x, y) }
        return true
    }

    override fun onStatus(text: String, phoneConnected: Boolean) {
        statusView.text = text
        overlay.visibility = if (phoneConnected) View.GONE else View.VISIBLE
        settingsSmall.visibility = if (phoneConnected) View.VISIBLE else View.GONE
        // Only animate while something is actually in progress.
        progress.visibility = if (text.startsWith("Подключите") || text.startsWith("Разрешите")) View.INVISIBLE else View.VISIBLE
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
