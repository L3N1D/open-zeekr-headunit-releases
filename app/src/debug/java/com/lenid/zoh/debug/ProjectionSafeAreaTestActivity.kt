package com.lenid.zoh.debug

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lenid.zoh.R
import com.lenid.zoh.app.SurfaceActivity
import com.lenid.zoh.utils.HeadUnitScreenConfig
import com.lenid.zoh.utils.Settings
import com.lenid.zoh.view.IProjectionView
import com.lenid.zoh.view.ProjectionViewScaler
import com.lenid.zoh.view.TextureProjectionView

/**
 * Debug-only margin-aware source. Unlike a phone-unaware H.264 clip, this draws all meaningful
 * content inside the canvas Android Auto would have been told to use. The safe frame must exactly
 * reach the card edges and the circles must stay round in both Zeekr launcher card sizes.
 */
class ProjectionSafeAreaTestActivity : SurfaceActivity() {

    private lateinit var settings: Settings
    private lateinit var projectionView: TextureProjectionView
    private var renderSurface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())

    private var oldResolution = 0
    private var oldViewMode = Settings.ViewMode.TEXTURE
    private var oldFitMode = Settings.VideoFitMode.FILL
    private var oldForcedScale = false
    private var oldFullscreenMode = Settings.FullscreenMode.IMMERSIVE

    private val logGeometry = object : Runnable {
        override fun run() {
            if (::projectionView.isInitialized) {
                renderSurface?.let(::drawSafeArea)
                Log.i(
                    TAG,
                    "display=${display?.displayId} " +
                        "surface=${projectionView.width}x${projectionView.height} " +
                        "usable=${HeadUnitScreenConfig.getUsableWidth()}x${HeadUnitScreenConfig.getUsableHeight()} " +
                        "margins=${HeadUnitScreenConfig.getWidthMargin()}x${HeadUnitScreenConfig.getHeightMargin()} " +
                        "scale=${projectionView.scaleX}x${projectionView.scaleY} " +
                        "pivot=${projectionView.pivotX}x${projectionView.pivotY} " +
                        "position=${projectionView.left},${projectionView.top} " +
                        "translation=${projectionView.translationX}x${projectionView.translationY}",
                )
                handler.postDelayed(this, 2000L)
            }
        }
    }

    private val surfaceCallbacks = object : IProjectionView.Callbacks {
        override fun onSurfaceCreated(surface: Surface) {
            renderSurface = surface
            projectionView.surfaceTexture?.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        }

        override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
            renderSurface = surface
            updateGeometry(width, height)
            drawSafeArea(surface)
        }

        override fun onSurfaceDestroyed(surface: Surface) {
            if (renderSurface === surface) renderSurface = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        oldResolution = settings.resolutionId
        oldViewMode = settings.viewMode
        oldFitMode = settings.videoFitMode
        oldForcedScale = settings.forcedScale
        oldFullscreenMode = settings.fullscreenMode

        settings.resolutionId = Settings.Resolution._1920x1080.id
        settings.viewMode = Settings.ViewMode.TEXTURE
        settings.videoFitMode = Settings.VideoFitMode.FILL
        settings.forcedScale = false
        settings.fullscreenMode = Settings.FullscreenMode.IMMERSIVE

        setContentView(R.layout.activity_headunit)
        findViewById<View>(R.id.loading_overlay).visibility = View.GONE
        val container = findViewById<FrameLayout>(R.id.container)
        projectionView = TextureProjectionView(this)
        container.addView(
            projectionView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        projectionView.addCallback(surfaceCallbacks)

        HeadUnitScreenConfig.onMarginsDiverged = null
        HeadUnitScreenConfig.init(this, resources.displayMetrics, settings)
        HeadUnitScreenConfig.lockResolution()
        handler.post(logGeometry)
    }

    private fun updateGeometry(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        HeadUnitScreenConfig.updateSurfaceDimensions(width, height, dimensionsAreViewBounds = true)
        HeadUnitScreenConfig.recordAnnouncedMargins(
            HeadUnitScreenConfig.getWidthMargin(),
            HeadUnitScreenConfig.getHeightMargin(),
        )
        projectionView.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        ProjectionViewScaler.updateScale(projectionView, VIDEO_WIDTH, VIDEO_HEIGHT)
    }

    private fun drawSafeArea(surface: Surface) {
        val left = HeadUnitScreenConfig.getLeftMargin()
        val top = HeadUnitScreenConfig.getTopMargin()
        val right = VIDEO_WIDTH - HeadUnitScreenConfig.getRightMargin()
        val bottom = VIDEO_HEIGHT - HeadUnitScreenConfig.getBottomMargin()
        val safe = Rect(left, top, right, bottom)
        if (safe.width() <= 0 || safe.height() <= 0) return

        // TextureView otherwise retains the previous card-sized Canvas transform after a resize.
        projectionView.surfaceTexture?.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        val canvas = try {
            surface.lockCanvas(Rect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT))
        } catch (error: Exception) {
            Log.e(TAG, "Cannot lock the test surface", error)
            return
        }
        val bufferWidth = canvas.width
        val bufferHeight = canvas.height
        try {
            drawPattern(canvas, safe)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        Log.i(
            TAG,
            "drawn buffer=${bufferWidth}x$bufferHeight safe=$safe " +
                "view=${projectionView.width}x${projectionView.height}",
        )
    }

    private fun drawPattern(canvas: Canvas, safe: Rect) {
        canvas.drawColor(Color.rgb(70, 18, 58))
        canvas.drawRect(safe, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(13, 28, 38)
            style = Paint.Style.FILL
        })

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(48, 75, 88)
            strokeWidth = 2f
        }
        for (i in 1 until 8) {
            val x = safe.left + safe.width() * i / 8f
            canvas.drawLine(x, safe.top.toFloat(), x, safe.bottom.toFloat(), grid)
        }
        for (i in 1 until 6) {
            val y = safe.top + safe.height() * i / 6f
            canvas.drawLine(safe.left.toFloat(), y, safe.right.toFloat(), y, grid)
        }

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(62, 235, 145)
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRect(safe, border)

        val centerX = safe.exactCenterX()
        val centerY = safe.exactCenterY()
        val radius = minOf(safe.width(), safe.height()) * 0.19f
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 165, 245)
            style = Paint.Style.STROKE
            strokeWidth = 12f
        }
        canvas.drawCircle(centerX, centerY, radius, circle)
        canvas.drawCircle(centerX, centerY, radius * 0.55f, border)

        val squareSize = radius * 0.55f
        canvas.drawRect(
            RectF(centerX - squareSize, centerY - squareSize, centerX + squareSize, centerY + squareSize),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) },
        )

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = safe.height() * 0.065f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("SAFE ${safe.width()} x ${safe.height()}", centerX, safe.top + safe.height() * 0.12f, text)
        canvas.drawText(
            "CARD ${projectionView.width} x ${projectionView.height}",
            centerX,
            safe.bottom - safe.height() * 0.07f,
            text,
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        projectionView.post {
            updateGeometry(projectionView.width, projectionView.height)
            renderSurface?.let(::drawSafeArea)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(logGeometry)
        if (::projectionView.isInitialized) projectionView.removeCallback(surfaceCallbacks)
        renderSurface = null
        settings.resolutionId = oldResolution
        settings.viewMode = oldViewMode
        settings.videoFitMode = oldFitMode
        settings.forcedScale = oldForcedScale
        settings.fullscreenMode = oldFullscreenMode
        HeadUnitScreenConfig.unlockResolution()
        HeadUnitScreenConfig.clearAnnouncedMargins()
        HeadUnitScreenConfig.onMarginsDiverged = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ZeekrSafeAreaTest"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
    }
}
