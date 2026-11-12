package com.lenid.zoh.carplay

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lenid.zoh.App
import com.lenid.zoh.R
import com.lenid.zoh.app.BaseActivity
import com.shilapi.xcertplay.airplay.AirPlayContact
import kotlinx.coroutines.*
import kotlin.math.min

/** Host UI only. The service owns transport, audio and decoder while windows change. */
class DirectCarPlayActivity : BaseActivity(), TextureView.SurfaceTextureListener {
    private var service: DirectCarPlayService? = null
    private var bound = false
    private var collecting: Job? = null
    private var surface: Surface? = null
    private lateinit var connectionView: CarPlayConnectionView
    private lateinit var texture: TextureView
    private lateinit var panel: com.lenid.zoh.utils.ConnectionPanel
    private var homeConnectConsumed = false
    private var pendingWireless = false
    private var exitAnimationFinished = false
    private var scale = 1f
    private var left = 0f
    private var top = 0f
    private var acceptingTouch = false
    private var lastContacts = emptyList<AirPlayContact>()
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) connect(false)
        else connectionView.setStatus(R.string.carplay_vpn_required)
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { granted -> granted }) connect(pendingWireless)
        else connectionView.setStatus(R.string.carplay_permissions_required)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as DirectCarPlayService.LocalBinder).service
            surface?.let { service?.attachSurface(it) }
            sendTheme()
            collecting = lifecycleScope.launch {
                service?.status?.collect { message -> connectionView.setStatus(message); refreshPresentation() }
            }
            refreshPresentation()
            if (!homeConnectConsumed && intent.getBooleanExtra(EXTRA_HOME_CONNECT, false)) {
                homeConnectConsumed = true
                if (service?.running != true) requestConnect(intent.getBooleanExtra(EXTRA_HOME_WIRELESS, false))
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; collecting?.cancel() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homeConnectConsumed = savedInstanceState?.getBoolean("homeConnectConsumed") ?: false
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        panel = com.lenid.zoh.utils.ConnectionPanel(this, R.string.home_carplay,
            R.string.carplay_subtitle, R.drawable.ic_carplay, window) { finish() }
        connectionView = CarPlayConnectionView(this, panel,
            onConnect = ::requestConnect,
            onStop = { service?.stopSession(); refreshPresentation() },
            onClose = { finish() })
        texture = TextureView(this).apply {
            surfaceTextureListener = this@DirectCarPlayActivity
            visibility = View.INVISIBLE
        }
        // The projection retains its full canvas; the connection card does not constrain it.
        panel.addProjectionView(texture)
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> releaseTouches(); refreshPresentation() }
        texture.setOnTouchListener { _, event -> onTouch(event) }
        setContentView(panel.root)
        connectionView.adaptOptions(resources.configuration.screenWidthDp)
        bound = bindService(Intent(this, DirectCarPlayService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun finish() {
        if (::panel.isInitialized && !exitAnimationFinished && !isDestroyed) {
            panel.dismiss { exitAnimationFinished = true; finish() }
            return
        }
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
    override fun onResume() {
        super.onResume()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }
    private fun requestConnect(wireless: Boolean) {
        if (service == null) return
        if (App.provide(this).commManager.isBusy) { connectionView.setStatus(R.string.carplay_disconnect_aa); return }
        if (!CarPlayAuthentication.present(this)) { connectionView.setStatus(R.string.carplay_auth_missing); return }
        pendingWireless = wireless
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (wireless) {
            if (Build.VERSION.SDK_INT >= 31) needed += listOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.NEARBY_WIFI_DEVICES
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) connect(wireless) else permissions.launch(missing.toTypedArray())
    }
    private fun connect(wireless: Boolean) {
        if (App.provide(this).commManager.isBusy) { connectionView.setStatus(R.string.carplay_disconnect_aa); return }
        if (!wireless) {
            intent.getStringExtra(EXTRA_HOME_USB)?.let { selected ->
                val phones = com.lenid.zoh.connection.usb.UsbDeviceCompat.usbManager(this)
                    ?.deviceList?.values.orEmpty().filter { it.vendorId == 0x05ac }
                if (phones.size != 1 || phones.single().deviceName != selected) {
                    connectionView.setStatus(R.string.home_device_gone)
                    return
                }
            }
            val consent = android.net.VpnService.prepare(this)
            if (consent != null) { vpnConsent.launch(consent); return }
        }
        val manager = getSystemService(DisplayManager::class.java)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        val fallback = resources.displayMetrics
        val panelW = metrics.widthPixels.takeIf { it > 0 } ?: fallback.widthPixels
        val panelH = metrics.heightPixels.takeIf { it > 0 } ?: fallback.heightPixels
        val canvas = CarPlayViewport(maxOf(panelW, panelH), minOf(panelW, panelH))
        val chrome = (texture.rootView.height - texture.height).coerceAtLeast(0)
        val windows = manager.displays.mapNotNull {
            val m = android.util.DisplayMetrics(); @Suppress("DEPRECATION") it.getRealMetrics(m)
            if (m.widthPixels >= 320 && m.heightPixels > chrome)
                CarPlayViewport(m.widthPixels, m.heightPixels - chrome) else null
        } + listOf(
            // Both launcher states must be advertised before pairing, even if only one exists now.
            CarPlayViewport(1275, (876 - chrome).coerceAtLeast(240)),
            CarPlayViewport(1796, (876 - chrome).coerceAtLeast(240)),
            CarPlayViewport(texture.width.coerceAtLeast(1), texture.height.coerceAtLeast(1)))
        service?.startSession(wireless, canvas, windows,
            intent.getStringExtra(EXTRA_HOME_ADDRESS).takeIf { wireless })
        refreshPresentation()
    }
    private fun refreshPresentation() {
        if (!::texture.isInitialized) return
        val owner = service ?: return
        connectionView.render(owner.connected, owner.running)
        texture.visibility = if (owner.connected) View.VISIBLE else View.INVISIBLE
        layoutVideo(owner)
    }

    private fun layoutVideo(owner: DirectCarPlayService) {
        if (texture.width == 0 || texture.height == 0) return
        val area = owner.selectArea(CarPlayViewport(texture.width, texture.height))
        val canvas = owner.canvas
        scale = min(texture.width.toFloat() / area.width, texture.height.toFloat() / area.height)
        left = (texture.width - area.width * scale) / 2f
        top = (texture.height - area.height * scale) / 2f
        texture.surfaceTexture?.setDefaultBufferSize(canvas.width, canvas.height)
        texture.setTransform(Matrix().apply {
            setScale(canvas.width * scale / texture.width, canvas.height * scale / texture.height)
            postTranslate(left, top)
        })
    }
    private fun onTouch(event: MotionEvent): Boolean {
        val owner = service ?: return false
        val area = owner.areas[owner.areaIndex]
        if (event.actionMasked == MotionEvent.ACTION_DOWN) acceptingTouch =
            event.x >= left && event.x < left + area.width * scale && event.y >= top && event.y < top + area.height * scale
        if (!acceptingTouch) return false
        val released = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        val contacts = (0 until event.pointerCount).map { i -> AirPlayContact(event.getPointerId(i),
            ((event.getX(i) - left) / (owner.canvas.width * scale)).toDouble().coerceIn(0.0, 1.0),
            ((event.getY(i) - top) / (owner.canvas.height * scale)).toDouble().coerceIn(0.0, 1.0),
            !released && !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex)) }
        owner.touch(contacts); lastContacts = contacts
        if (released) acceptingTouch = false
        return true
    }
    private fun releaseTouches() {
        if (lastContacts.any { it.down }) service?.touch(lastContacts.map { it.copy(down = false) })
        lastContacts = emptyList(); acceptingTouch = false
    }
    private fun sendTheme() = service?.themeChanged(resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); sendTheme(); connectionView.adaptOptions(resources.configuration.screenWidthDp); refreshPresentation() }
    override fun onSurfaceTextureAvailable(value: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(value).also { service?.attachSurface(it) }; refreshPresentation()
    }
    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, w: Int, h: Int) { refreshPresentation() }
    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
        releaseTouches(); surface?.let { service?.detachSurface(it); it.release() }; surface = null; return true
    }
    override fun onSurfaceTextureUpdated(value: SurfaceTexture) {}
    override fun onDestroy() {
        releaseTouches(); surface?.let { service?.detachSurface(it); it.release() }; surface = null
        collecting?.cancel()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("homeConnectConsumed", homeConnectConsumed)
        super.onSaveInstanceState(outState)
    }
    companion object {
        const val EXTRA_HOME_CONNECT = "com.lenid.zoh.carplay.HOME_CONNECT"
        const val EXTRA_HOME_WIRELESS = "com.lenid.zoh.carplay.HOME_WIRELESS"
        const val EXTRA_HOME_USB = "com.lenid.zoh.carplay.HOME_USB"
        const val EXTRA_HOME_ADDRESS = "com.lenid.zoh.carplay.HOME_ADDRESS"
    }

}
