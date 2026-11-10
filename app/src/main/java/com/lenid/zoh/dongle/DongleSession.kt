package com.lenid.zoh.dongle

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DongleConfig(
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
class DongleSession(
    private val link: DongleTransport,
    private val onMessage: (type: Int, payload: ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    @Volatile private var running = false
    private val writeLock = Object()

    fun start(cfg: DongleConfig) {
        running = true
        Thread(::readLoop, "dongle-read").apply { priority = Thread.MAX_PRIORITY }.start()
        handshake(cfg)
        Thread(::heartbeatLoop, "dongle-hb").start()
    }

    private fun handshake(c: DongleConfig) {
        sendFile("/tmp/screen_dpi", DongleProtocol.int(c.dpi))
        send(DongleProtocol.OPEN, DongleProtocol.open(c.width, c.height, c.fps))
        setNight(c.night)
        sendFile("/tmp/hand_drive_mode", DongleProtocol.int(if (c.rightHandDrive) 1 else 0))
        sendFile("/tmp/charge_mode", DongleProtocol.int(1))
        sendFile("/etc/box_name", c.dongleName.toByteArray())
        val settings = JSONObject()
            .put("mediaDelay", c.mediaDelay)
            .put("syncTime", System.currentTimeMillis() / 1000)
            .put("androidAutoSizeW", c.width)
            .put("androidAutoSizeH", c.height)
        send(DongleProtocol.BOX_SETTINGS, settings.toString().toByteArray())
        command(DongleProtocol.CMD_WIFI_ENABLE)
        command(if (c.wifi5g) DongleProtocol.CMD_WIFI_5G else DongleProtocol.CMD_WIFI_24G)
        command(if (c.dongleMic) DongleProtocol.CMD_MIC_DONGLE else DongleProtocol.CMD_MIC_HOST)
        command(if (c.bluetoothAudio) DongleProtocol.CMD_AUDIO_BT else DongleProtocol.CMD_AUDIO_USB)
        command(DongleProtocol.CMD_WIFI_CONNECT)
    }

    fun setNight(night: Boolean) = sendFile("/tmp/night_mode", DongleProtocol.int(if (night) 1 else 0))

    fun command(c: Int) = send(DongleProtocol.COMMAND, DongleProtocol.int(c))

    fun sendFile(name: String, content: ByteArray) = send(DongleProtocol.SEND_FILE, DongleProtocol.file(name, content))

    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        if (!running) return
        val buf = DongleProtocol.header(type, payload.size) + payload
        val ok = synchronized(writeLock) { link.write(buf, 0, buf.size) }
        if (!ok) { Log.w(TAG, "write failed type=$type"); close() }
    }

    private fun heartbeatLoop() {
        while (running) {
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
            send(DongleProtocol.HEARTBEAT)
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
                if (!fill(DongleProtocol.HEADER_SIZE)) break
                val h = ByteBuffer.wrap(rx, rxStart, DongleProtocol.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                val magic = h.int; val len = h.int; val type = h.int
                if (magic != DongleProtocol.MAGIC || len < 0 || len > rx.size - DongleProtocol.HEADER_SIZE) {
                    Log.w(TAG, "bad header, resync"); rxStart++; continue
                }
                rxStart += DongleProtocol.HEADER_SIZE
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

    companion object { const val TAG = "Dongle" }
}
