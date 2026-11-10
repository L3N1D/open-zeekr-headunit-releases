package com.lenid.carlink

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Config(
    val width: Int,
    val height: Int,
    val fps: Int,
    val dpi: Int,
    val night: Boolean,
    val rightHandDrive: Boolean = false,
    val wifi5g: Boolean = true,
    val dongleMic: Boolean = false,
    val bluetoothAudio: Boolean = false,
    val mediaDelay: Int = 300,
    val dongleName: String = "Zeekr",
)

/** One session with the dongle over [link]. [onMessage] is called on the reader thread. */
class Dongle(
    private val link: Transport,
    private val onMessage: (type: Int, payload: ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    @Volatile private var running = false
    private val writeLock = Object()

    fun start(cfg: Config) {
        running = true
        Thread(::readLoop, "carlink-read").apply { priority = Thread.MAX_PRIORITY }.start()
        handshake(cfg)
        Thread(::heartbeatLoop, "carlink-hb").start()
    }

    private fun handshake(c: Config) {
        sendFile("/tmp/screen_dpi", Protocol.int(c.dpi))
        send(Protocol.OPEN, Protocol.open(c.width, c.height, c.fps))
        setNight(c.night)
        sendFile("/tmp/hand_drive_mode", Protocol.int(if (c.rightHandDrive) 1 else 0))
        sendFile("/tmp/charge_mode", Protocol.int(1))
        sendFile("/etc/box_name", c.dongleName.toByteArray())
        val settings = JSONObject()
            .put("mediaDelay", c.mediaDelay)
            .put("syncTime", System.currentTimeMillis() / 1000)
            .put("androidAutoSizeW", c.width)
            .put("androidAutoSizeH", c.height)
        send(Protocol.BOX_SETTINGS, settings.toString().toByteArray())
        command(Protocol.CMD_WIFI_ENABLE)
        command(if (c.wifi5g) Protocol.CMD_WIFI_5G else Protocol.CMD_WIFI_24G)
        command(if (c.dongleMic) Protocol.CMD_MIC_DONGLE else Protocol.CMD_MIC_HOST)
        command(if (c.bluetoothAudio) Protocol.CMD_AUDIO_BT else Protocol.CMD_AUDIO_USB)
        command(Protocol.CMD_WIFI_CONNECT)
    }

    fun setNight(night: Boolean) = sendFile("/tmp/night_mode", Protocol.int(if (night) 1 else 0))

    fun command(c: Int) = send(Protocol.COMMAND, Protocol.int(c))

    fun sendFile(name: String, content: ByteArray) = send(Protocol.SEND_FILE, Protocol.file(name, content))

    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        if (!running) return
        val buf = Protocol.header(type, payload.size) + payload
        val ok = synchronized(writeLock) { link.write(buf, 0, buf.size) }
        if (!ok) { Log.w(TAG, "write failed type=$type"); close() }
    }

    private fun heartbeatLoop() {
        while (running) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            send(Protocol.HEARTBEAT)
        }
    }

    // Reads return arbitrary chunks; buffer them and cut out whole messages.
    private val rx = ByteArray(1 shl 20)
    private var rxStart = 0
    private var rxEnd = 0

    private fun fill(need: Int): Boolean {
        if (rxEnd - rxStart >= need) return true
        if (rxStart > 0) {
            System.arraycopy(rx, rxStart, rx, 0, rxEnd - rxStart)
            rxEnd -= rxStart; rxStart = 0
        }
        while (running && rxEnd < need) {
            val n = link.read(rx, rxEnd, rx.size - rxEnd)
            if (n < 0) return false
            rxEnd += n
        }
        return running
    }

    private fun readLoop() {
        try {
            while (running) {
                if (!fill(Protocol.HEADER_SIZE)) break
                val h = ByteBuffer.wrap(rx, rxStart, Protocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val magic = h.int; val len = h.int; val type = h.int
                if (magic != Protocol.MAGIC || len < 0 || len > rx.size - Protocol.HEADER_SIZE) {
                    Log.w(TAG, "bad header, resync"); rxStart++; continue
                }
                rxStart += Protocol.HEADER_SIZE
                if (!fill(len)) break
                val payload = rx.copyOfRange(rxStart, rxStart + len)
                rxStart += len
                try { onMessage(type, payload) } catch (e: Exception) { Log.e(TAG, "handler type=$type", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "read loop", e)
        }
        close()
    }

    @Synchronized
    fun close() {
        if (!running) return
        running = false
        link.close()
        onClosed()
    }

    companion object { const val TAG = "Carlink" }
}
