package com.lenid.zoh.debug

import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lenid.zoh.R
import com.lenid.zoh.app.SurfaceActivity
import com.lenid.zoh.decoder.video.VideoDecoder
import com.lenid.zoh.decoder.video.VideoDimensionsListener
import com.lenid.zoh.decoder.video.preflight.AnnexBAccessUnits
import com.lenid.zoh.decoder.video.preflight.VideoPreflightClip
import com.lenid.zoh.utils.HeadUnitScreenConfig
import com.lenid.zoh.utils.Settings
import com.lenid.zoh.view.IProjectionView
import com.lenid.zoh.view.ProjectionViewScaler
import com.lenid.zoh.view.TextureProjectionView

/** Debug-only synthetic video source for checking projection geometry without a phone. */
class ProjectionScaleTestActivity : SurfaceActivity(), VideoDimensionsListener {

    private lateinit var settings: Settings
    private lateinit var projectionView: TextureProjectionView
    private lateinit var decoder: VideoDecoder

    @Volatile private var renderSurface: Surface? = null
    @Volatile private var feeding = false
    private var feedThread: Thread? = null

    private var oldResolution = 0
    private var oldViewMode = Settings.ViewMode.TEXTURE
    private var oldFitMode = Settings.VideoFitMode.FILL
    private var oldForcedScale = false
    private var oldFullscreenMode = Settings.FullscreenMode.IMMERSIVE

    private val surfaceCallbacks = object : IProjectionView.Callbacks {
        override fun onSurfaceCreated(surface: Surface) = Unit

        override fun onSurfaceChanged(surface: Surface, width: Int, height: Int) {
            renderSurface = surface
            decoder.setSurface(surface)
            updateGeometry(width, height)
        }

        override fun onSurfaceDestroyed(surface: Surface) {
            if (renderSurface === surface) {
                renderSurface = null
                decoder.setSurface(null)
            }
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

        decoder = VideoDecoder(settings)
        decoder.dimensionsListener = this
        projectionView.addCallback(surfaceCallbacks)

        HeadUnitScreenConfig.onMarginsDiverged = null
        HeadUnitScreenConfig.init(this, resources.displayMetrics, settings)
        HeadUnitScreenConfig.lockResolution()
        startFeed()
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
        projectionView.post {
            Log.i(
                TAG,
                "display=${display?.displayId} surface=${width}x$height " +
                    "usable=${HeadUnitScreenConfig.getUsableWidth()}x${HeadUnitScreenConfig.getUsableHeight()} " +
                    "margins=${HeadUnitScreenConfig.getWidthMargin()}x${HeadUnitScreenConfig.getHeightMargin()} " +
                    "view=${projectionView.width}x${projectionView.height} " +
                    "scale=${projectionView.scaleX}x${projectionView.scaleY} " +
                    "frames=${projectionView.drawnFrames()}",
            )
        }
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            ProjectionViewScaler.updateScale(projectionView, width, height)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        projectionView.post { updateGeometry(projectionView.width, projectionView.height) }
    }

    private fun startFeed() {
        feeding = true
        feedThread = Thread({
            val clip = VideoPreflightClip.H264_1080P
            val data = resources.openRawResource(clip.rawResource).use { it.readBytes() }
            val units = AnnexBAccessUnits.split(data, clip.codec)
            if (units.size != clip.frameCount) {
                Log.e(TAG, "Expected ${clip.frameCount} access units, found ${units.size}")
                return@Thread
            }
            val first = units.first()
            val configLength = AnnexBAccessUnits.parameterSetPrefix(data, first, clip.codec)
            var firstPass = true
            try {
                while (feeding) {
                    if (renderSurface == null) {
                        SystemClock.sleep(50)
                        continue
                    }
                    units.forEachIndexed { index, unit ->
                        if (!feeding || renderSurface == null) return@forEachIndexed
                        val skipConfig = firstPass && index == 0
                        val offset = unit.offset + if (skipConfig) configLength else 0
                        val length = unit.length - if (skipConfig) configLength else 0
                        if (length > 0) {
                            decoder.decode(data, offset, length, false, clip.codec.settingsValue)
                        }
                        SystemClock.sleep(1000L / clip.fps)
                    }
                    firstPass = false
                }
            } catch (error: Exception) {
                if (feeding) Log.e(TAG, "Synthetic video feed failed", error)
            }
        }, "debug-projection-scale-feed")
        feedThread?.start()
    }

    override fun onDestroy() {
        feeding = false
        feedThread?.interrupt()
        projectionView.removeCallback(surfaceCallbacks)
        decoder.dimensionsListener = null
        decoder.setSurface(null)
        decoder.stop("debug_projection_scale_test")
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
        private const val TAG = "ZeekrScaleTest"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
    }
}
