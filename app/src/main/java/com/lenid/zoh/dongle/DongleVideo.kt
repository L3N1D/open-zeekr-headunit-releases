package com.lenid.zoh.dongle

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Process
import com.lenid.zoh.decoder.video.LowLatencyFormat
import com.lenid.zoh.utils.AppLog
import android.view.Surface

/** H.264 decoder onto a Surface. [feed] is called from the USB reader thread. */
class DongleVideo(
    private val requestKeyframe: () -> Unit,
    private val onFrameSize: (width: Int, height: Int) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var waitKeyframe = true
    private var drainThread: Thread? = null
    @Volatile var hasFrames = false
        private set

    @Synchronized
    fun setSurface(s: Surface?) {
        stopCodec()
        surface = s
        hasFrames = false
        if (s != null) requestKeyframe()
    }

    @Synchronized
    fun feed(payload: ByteArray) {
        if (payload.size <= 20) return
        val s = surface ?: return
        val w = le(payload, 0)
        val h = le(payload, 4)
        if (codec == null || w != width || h != height) {
            stopCodec()
            startCodec(s, w, h)
            onFrameSize(w, h)
        }
        if (waitKeyframe) {
            if (!containsSps(payload, 20)) { requestKeyframe(); return }
            waitKeyframe = false
        }
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(50_000)
            if (idx < 0) return
            val buf = c.getInputBuffer(idx)!!
            buf.clear()
            val n = minOf(payload.size - 20, buf.remaining())
            buf.put(payload, 20, n)
            c.queueInputBuffer(idx, 0, n, System.nanoTime() / 1000, 0)
        } catch (e: Exception) {
            AppLog.e("[$TAG] feed: ${e.message}")
            stopCodec()
        }
    }

    private fun startCodec(s: Surface, w: Int, h: Int) {
        width = w; height = h
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        LowLatencyFormat.apply(fmt, c.name)
        c.configure(fmt, s, null, 0)
        c.start()
        codec = c
        waitKeyframe = true
        AppLog.i("[$TAG] decoder ${c.name} started ${w}x$h")
        drainThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            drain(c)
        }, "dongle-video").apply { start() }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val idx = c.dequeueOutputBuffer(info, 100_000)
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true)
                    hasFrames = true
                }
            }
        } catch (_: IllegalStateException) {
            // codec stopped
        }
    }

    private fun stopCodec() {
        val c = codec ?: return
        codec = null
        drainThread?.interrupt()
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
        drainThread?.join(500)
        drainThread = null
    }

    @Synchronized
    fun release() { stopCodec(); surface = null }

    private fun containsSps(b: ByteArray, from: Int): Boolean {
        var i = from
        val end = minOf(b.size - 4, from + 512)
        while (i < end) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 && (b[i + 3].toInt() and 0x1F) == 7) return true
            i++
        }
        return false
    }

    companion object {
        const val TAG = "DongleVideo"
        fun le(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
