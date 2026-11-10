package com.lenid.zoh.dongle

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lenid.zoh.R
import com.lenid.zoh.app.BaseActivity
import com.lenid.zoh.main.MainActivity
import com.lenid.zoh.main.SettingsActivity
import com.lenid.zoh.main.SettingsFragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The dongle's picture with touch. Leaving it (Home or Back) keeps the session and its sound going
 * in DongleService; coming back asks the dongle for a keyframe, so the picture is there at once.
 */
class DongleActivity : BaseActivity(), SurfaceHolder.Callback, DongleService.Listener {
    private var service: DongleService? = null
    private lateinit var settings: DongleSettings
    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: View
    private lateinit var statusView: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var homeSmall: View
    private var screenW = 0
    private var screenH = 0
    private var surfaceReady = false
    private val touchSender = Executors.newSingleThreadExecutor()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as DongleService.LocalBinder).service
            service = s
            s.listener = this@DongleActivity
            s.setScreenSize(screenW, screenH)
            layoutVideo()
            if (surfaceReady) s.setSurface(surfaceView.holder.surface)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = DongleSettings(this)
        // Plugged in with "open automatically" off: start the session for its sound, stay where we are.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED && !settings.autoOpen) {
            DongleService.start(this)
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_dongle)
        root = findViewById(R.id.dongle_root)
        surfaceView = findViewById(R.id.dongle_video)
        overlay = findViewById(R.id.dongle_overlay)
        statusView = findViewById(R.id.dongle_status)
        progress = findViewById(R.id.dongle_progress)
        homeSmall = findViewById(R.id.dongle_home_small)
        homeSmall.setOnClickListener { goHome() }
        findViewById<View>(R.id.dongle_home).setOnClickListener { goHome() }
        findViewById<View>(R.id.dongle_settings).setOnClickListener {
            SettingsFragment.openAt(DONGLE_CATEGORY)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { v, e -> onTouch(v, e) }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                screenW = r - l; screenH = b - t
                service?.setScreenSize(screenW, screenH)
                layoutVideo()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        DongleService.start(this)
        bindService(Intent(this, DongleService::class.java), conn, BIND_AUTO_CREATE)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DongleService.state.collect { onState(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        // The AA / CarPlay side rail moves to the right for right-hand drive; keep the button on it.
        (homeSmall.layoutParams as FrameLayout.LayoutParams).gravity =
            Gravity.TOP or if (settings.rightHandDrive) Gravity.END else Gravity.START
        homeSmall.requestLayout()
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
        if (::root.isInitialized) {
            service?.listener = null
            service?.setSurface(null)
            unbindService(conn)
        }
        touchSender.shutdown()
        super.onDestroy()
    }

    // The session (and its sound) stays in the service; only the screen goes.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goHome()

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
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
            MotionEvent.ACTION_DOWN -> DongleProtocol.TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> DongleProtocol.TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> DongleProtocol.TOUCH_UP
            else -> return true
        }
        val x = e.x / v.width
        val y = e.y / v.height
        touchSender.execute { s.touch(action, x, y) }
        return true
    }

    private fun onState(state: DongleService.State) {
        statusView.text = state.status
        overlay.visibility = if (state.phoneConnected) View.GONE else View.VISIBLE
        homeSmall.visibility = if (state.phoneConnected) View.VISIBLE else View.GONE
        // Only animate while something is actually in progress.
        progress.visibility = if (state.waitingOnDriver) View.INVISIBLE else View.VISIBLE
    }

    companion object {
        /** The settings tab with everything the dongle is told in the handshake. */
        const val DONGLE_CATEGORY = "dongle"
    }
}
