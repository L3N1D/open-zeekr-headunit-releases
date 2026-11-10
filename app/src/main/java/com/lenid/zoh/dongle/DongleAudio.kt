package com.lenid.zoh.dongle

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.lenid.zoh.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/** PCM playback per stream (media / navigation / voice) and microphone capture for calls and assistant. */
class DongleAudio(private val sendMic: (ByteArray, Int) -> Unit) {
    private val tracks = HashMap<Int, AudioTrack>()
    private var mic: Thread? = null
    private var micUsers = 0
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val worker = Thread({
        try {
            while (true) handle(queue.take())
        } catch (_: InterruptedException) {
        }
        tracks.values.forEach { try { it.stop(); it.release() } catch (_: Exception) {} }
        tracks.clear()
    }, "dongle-audio").apply { priority = Thread.MAX_PRIORITY - 1; start() }

    /** Called from the USB reader thread; playback happens on the audio worker. */
    fun onPacket(p: ByteArray) {
        if (queue.size > 100) queue.poll() // fall behind -> drop oldest instead of growing latency
        queue.offer(p)
    }

    private fun handle(p: ByteArray) {
        if (p.size < 12) return
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val decodeType = bb.int
        bb.float // volume
        val audioType = bb.int
        val dataLen = p.size - 12
        when (dataLen) {
            1 -> onCommand(p[12].toInt())
            4 -> Unit // volume ramp duration
            else -> if (dataLen > 0) play(decodeType, audioType, p, 12, dataLen)
        }
    }

    private fun onCommand(cmd: Int) {
        when (cmd) {
            DongleProtocol.AUDIO_PHONECALL_START, DongleProtocol.AUDIO_SIRI_START -> { micUsers++; startMic() }
            DongleProtocol.AUDIO_PHONECALL_STOP, DongleProtocol.AUDIO_SIRI_STOP -> {
                micUsers = maxOf(0, micUsers - 1)
                if (micUsers == 0) stopMic()
            }
        }
    }

    private fun play(decodeType: Int, audioType: Int, p: ByteArray, off: Int, len: Int) {
        val key = audioType * 100 + decodeType
        val t = tracks.getOrPut(key) { createTrack(decodeType, audioType) }
        t.write(p, off, len, AudioTrack.WRITE_BLOCKING)
    }

    private fun createTrack(decodeType: Int, audioType: Int): AudioTrack {
        val (rate, channels) = when (decodeType) {
            3 -> 8000 to 1
            4 -> 48000 to 2
            5 -> 16000 to 1
            6 -> 24000 to 1
            7 -> 16000 to 2
            else -> 44100 to 2
        }
        val usage = when (audioType) {
            2 -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            3 -> AudioAttributes.USAGE_VOICE_COMMUNICATION
            else -> AudioAttributes.USAGE_MEDIA
        }
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        AppLog.i("[$TAG] track type=$audioType decode=$decodeType ${rate}Hz x$channels")
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
                .setContentType(if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, rate * channels * 2 / 5)) // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (mic != null) return
        mic = Thread({
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
            } catch (e: Exception) { AppLog.e("[$TAG] mic: ${e.message}"); return@Thread }
            val buf = ByteArray(640) // 20 ms
            try {
                rec.startRecording()
                while (!Thread.currentThread().isInterrupted) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) sendMic(buf, n)
                }
            } catch (e: Exception) {
                AppLog.e("[$TAG] mic: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                rec.release()
            }
        }, "dongle-mic").apply { start() }
    }

    private fun stopMic() { mic?.interrupt(); mic = null }

    fun release() {
        stopMic(); micUsers = 0
        worker.interrupt()
    }

    companion object { const val TAG = "DongleAudio" }
}
