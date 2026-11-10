package com.lenid.zoh.mirror

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import com.lenid.zoh.decoder.video.LowLatencyFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.lenid.zoh.aap.AapMessage
import com.lenid.zoh.utils.AppLog
import java.util.ArrayDeque

/**
 * The phone's second video stream, the one Android Auto renders for an instrument cluster (a nav
 * app's own cluster map, not a copy of the main screen). Announced as a separate media sink with
 * display type CLUSTER when the "OH Route Map" app asks for it, and decoded here into the HUD
 * Surface that app hands over through [HudMirrorService] (codes 5/6/7).
 *
 * Until a Surface arrives the codec decodes into a throwaway ImageReader, so attaching later
 * swaps the output without waiting for the next keyframe. API 23+ (setOutputSurface).
 */
object ClusterVideo {
    const val WIDTH = 800
    const val HEIGHT = 480
    const val DENSITY = 160
    const val DISPLAY_ID = 1

    /** Frames queued for the codec; past this the oldest go, the picture recovers at the next IDR. */
    private const val BACKLOG_LIMIT = 30
    private const val KEYFRAME_ASK_MS = 1000L

    private val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Codec state, touched only on [handler].
    private var codec: MediaCodec? = null
    private var dummyReader: ImageReader? = null
    private var output: Surface? = null
    private var csd: ByteArray? = null
    private val pending = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()
    private var frames = 0L
    private var rendered = 0L
    /** Frames thrown away (a backlog cleared, or waiting for a keyframe), for the log. */
    private var discarded = 0L

    /**
     * A backlog was dropped: frames after it refer to pictures the decoder never got, which
     * smears the map into blocks until the next keyframe. So nothing goes in until one comes,
     * and the last clean picture stays up meanwhile.
     */
    private var awaitingKeyframe = false
    private var keyframeAskedAt = 0L

    /** Asks the phone for a keyframe on the cluster channel; set by the control channel at start. */
    @Volatile
    var requestKeyframe: (() -> Unit)? = null

    private class Frame(val data: ByteArray, val config: Boolean, val ptsUs: Long)

    @Volatile
    private var surface: Surface? = null

    /** The phone started the stream (MediaStart) and has not stopped it. */
    @Volatile
    var streaming = false
        private set

    /** The part of the [WIDTH]x[HEIGHT] frame the phone draws in, centred; the rest is margin. */
    data class Shape(
        val contentWidth: Int, val contentHeight: Int,
        /** The HUD cuts the map to a circle on the shorter side; panels belong inside it. */
        val round: Boolean = false,
        /** Announced dpi: the route map app's hint size; higher draws the phone's hints larger. */
        val density: Int = DENSITY,
        /** For a round map: share (0…100) of the inscribed square's margin kept free of panels. */
        val insetPercent: Int = 100,
        /** The same for the bottom edge alone, where the nav app puts its time/arrival panel. */
        val insetBottomPercent: Int = 100
    ) {
        val marginWidth get() = WIDTH - contentWidth
        val marginHeight get() = HEIGHT - contentHeight

        /**
         * Content insets in content pixels: none, or for a round map the square inscribed in the
         * circle, so the nav app draws its panels where the circle does not cut them.
         */
        val safeInsets: IntArray get() {
            if (!round) return intArrayOf(0, 0, 0, 0)
            val side = (minOf(contentWidth, contentHeight) / Math.sqrt(2.0)).toInt()
            val h = (contentWidth - side) / 2 * insetPercent / 100
            val v = (contentHeight - side) / 2 * insetPercent / 100
            val bottom = (contentHeight - side) / 2 * insetBottomPercent / 100
            return intArrayOf(v, bottom, h, h) // top, bottom, left, right
        }
    }

    /** What the last service discovery announced; the route map app scales its view to it. */
    @Volatile
    var announced: Shape? = null

    /**
     * Whether to announce the cluster sink (the route map app's setting, off without the app) and
     * in what shape: that of the app's HUD window, so the map is not stretched to fit it.
     */
    fun wanted(context: Context): Shape? {
        if (!supported) return null
        return try {
            context.contentResolver.query(Uri.parse("content://com.lenid.ohroutemap.settings/cluster"),
                null, null, null, null)?.use { c ->
                if (!c.moveToFirst() || c.getInt(0) == 0) return null
                val w = if (c.columnCount > 2) c.getInt(1) else 0
                val h = if (c.columnCount > 2) c.getInt(2) else 0
                val round = c.columnCount > 3 && c.getInt(3) != 0
                val density = if (c.columnCount > 4) c.getInt(4).coerceIn(80, 320) else DENSITY
                val inset = if (c.columnCount > 5) c.getInt(5).coerceIn(0, 100) else 100
                val insetBottom = if (c.columnCount > 6) c.getInt(6).coerceIn(0, 100) else inset
                shapeFor(w, h).copy(round = round, density = density, insetPercent = inset,
                    insetBottomPercent = insetBottom)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Largest centred area of the frame with the window's aspect ratio; whole frame if unknown. */
    fun shapeFor(windowWidth: Int, windowHeight: Int): Shape {
        if (windowWidth <= 0 || windowHeight <= 0) return Shape(WIDTH, HEIGHT)
        // Even sizes, so the margins split evenly between the two sides.
        return if (windowWidth * HEIGHT < windowHeight * WIDTH) {
            Shape((HEIGHT * windowWidth / windowHeight) and 1.inv(), HEIGHT)
        } else {
            Shape(WIDTH, (WIDTH * windowHeight / windowWidth) and 1.inv())
        }
    }

    fun attachSurface(s: Surface) {
        surface = s
        post { switchOutput() }
    }

    fun detachSurface() {
        surface = null
        post { switchOutput() }
    }

    fun onStart() {
        streaming = true
        AppLog.i("ClusterVideo: stream started")
    }

    fun onStop() {
        streaming = false
        requestKeyframe = null
        AppLog.i("ClusterVideo: stream stopped after $frames frames, $rendered rendered")
        post { releaseCodec(); pending.clear() }
    }

    /** Transport closed: drop everything, including the codec config of this session. */
    fun reset() {
        streaming = false
        requestKeyframe = null
        post { releaseCodec(); pending.clear(); csd = null; awaitingKeyframe = false }
    }

    /**
     * A complete media message from the cluster channel: type 1 is codec config (SPS/PPS), type 0
     * a frame after an 8-byte timestamp. The bytes are copied, so the caller may ack at once.
     */
    fun process(message: AapMessage) {
        if (!supported) return
        val config = message.type == 1
        val offset = message.dataOffset + if (config) 0 else 8
        val length = message.size - offset
        if (length <= 0) return
        val bytes = message.data.copyOfRange(offset, offset + length)
        val pts = if (config) 0L else readTimestamp(message.data, message.dataOffset)
        post { enqueue(Frame(bytes, config, pts)) }
    }

    private fun readTimestamp(buf: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[at + i].toLong() and 0xff)
        return v
    }

    private fun post(block: () -> Unit) {
        val h = synchronized(this) {
            handler ?: run {
                val t = HandlerThread("ClusterVideo", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
                thread = t
                Handler(t.looper).also { handler = it }
            }
        }
        h.post {
            try {
                block()
            } catch (e: Exception) {
                AppLog.e("ClusterVideo: ${e.message}", e)
                releaseCodec()
            }
        }
    }

    private fun enqueue(frame: Frame) {
        if (frame.config) {
            csd = frame.data
            // New parameters mean a new stream; start the codec over with them.
            releaseCodec()
            pending.clear()
            awaitingKeyframe = false
            ensureCodec()
            return
        }
        frames++
        if (codec == null && !ensureCodec()) return
        if (awaitingKeyframe) {
            if (!isKeyframe(frame.data)) {
                discarded++
                askForKeyframe()
                return
            }
            awaitingKeyframe = false
            AppLog.i("ClusterVideo: keyframe, decoding again")
        }
        pending.addLast(frame)
        if (pending.size > BACKLOG_LIMIT) {
            // The decoder is stuck behind its output (the HUD takes frames slowly). Dropping a
            // frame here and there broke the pictures after it, so all of them go and decoding
            // picks up again at a keyframe. A queued codec config stays: without it nothing decodes.
            val it = pending.iterator()
            while (it.hasNext()) if (!it.next().config) { it.remove(); discarded++ }
            awaitingKeyframe = true
            AppLog.w("ClusterVideo: backlog of $BACKLOG_LIMIT frames dropped, waiting for a keyframe")
            askForKeyframe()
            return
        }
        feed()
    }

    /** At most every [KEYFRAME_ASK_MS]: the phone answers a focus nudge with a keyframe. */
    private fun askForKeyframe() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - keyframeAskedAt < KEYFRAME_ASK_MS) return
        keyframeAskedAt = now
        requestKeyframe?.invoke()
    }

    /** An H.264 access unit (Annex B) holding an IDR slice, NAL type 5. */
    private fun isKeyframe(b: ByteArray): Boolean {
        var i = 0
        while (i + 3 < b.size) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                if (b[i + 3].toInt() and 0x1f == 5) return true
                i += 3
            } else i++
        }
        return false
    }

    private fun ensureCodec(): Boolean {
        if (codec != null) return true
        val config = csd ?: return false
        val out = surface?.takeIf { it.isValid } ?: dummySurface()
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (codec !== this@ClusterVideo.codec) return
                freeInputs.addLast(index)
                feed()
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (codec !== this@ClusterVideo.codec) return
                val toSurface = output !== dummyReader?.surface
                // Stamped "show now": the compositor then skips a frame a newer one overtook and
                // hands its buffer straight back, instead of queueing them all, which stalled the
                // decoder behind the HUD's slow compositor. (The phone's own timestamps are on
                // another clock.)
                if (toSurface) codec.releaseOutputBuffer(index, System.nanoTime())
                else codec.releaseOutputBuffer(index, true)
                if (toSurface) rendered++
                logProgress()
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                AppLog.e("ClusterVideo: codec error ${e.diagnosticInfo}")
                if (codec === this@ClusterVideo.codec) releaseCodec()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                AppLog.i("ClusterVideo: output format $format")
            }
        }, handler)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT)
        LowLatencyFormat.apply(format, c.name)
        c.configure(format, out, null, 0)
        codec = c
        output = out
        freeInputs.clear()
        c.start()
        pending.addFirst(Frame(config, true, 0))
        AppLog.i("ClusterVideo: decoder started (${if (out === surface) "HUD surface" else "no surface yet"})")
        return true
    }

    private var lastLogAt = 0L
    private var lastLogFrames = 0L
    private var lastLogRendered = 0L
    private var lastLogDiscarded = 0L

    /** Every 10 s while frames come: how many arrived and how many reached the HUD. */
    private fun logProgress() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogAt < 10_000) return
        if (lastLogAt != 0L) {
            AppLog.i("ClusterVideo: ${frames - lastLogFrames} frames in, ${rendered - lastLogRendered} to the HUD, " +
                "${discarded - lastLogDiscarded} discarded over ${(now - lastLogAt) / 1000}s")
        }
        lastLogAt = now
        lastLogFrames = frames
        lastLogRendered = rendered
        lastLogDiscarded = discarded
    }

    private fun feed() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val frame = pending.removeFirst()
            val index = freeInputs.removeFirst()
            val buffer = c.getInputBuffer(index) ?: continue
            if (frame.data.size > buffer.capacity()) {
                AppLog.w("ClusterVideo: ${frame.data.size}-byte frame over the ${buffer.capacity()}-byte input buffer")
                freeInputs.addFirst(index)
                continue
            }
            buffer.clear()
            buffer.put(frame.data)
            c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs,
                if (frame.config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
        }
    }

    private fun switchOutput() {
        val c = codec ?: return
        val target = surface?.takeIf { it.isValid } ?: dummySurface()
        if (target === output) return
        try {
            c.setOutputSurface(target)
            output = target
            AppLog.i("ClusterVideo: output now ${if (target === surface) "HUD surface" else "detached"}")
        } catch (e: Exception) {
            // Some decoders refuse the swap; a fresh codec on the next keyframe does the same job.
            AppLog.w("ClusterVideo: output swap failed (${e.message}), restarting the decoder")
            releaseCodec()
            ensureCodec()
        }
    }

    private fun dummySurface(): Surface {
        val reader = dummyReader ?: ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.PRIVATE, 2).also { r ->
            r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            dummyReader = r
        }
        return reader.surface
    }

    private fun releaseCodec() {
        val c = codec ?: return
        codec = null
        output = null
        freeInputs.clear()
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }
}
