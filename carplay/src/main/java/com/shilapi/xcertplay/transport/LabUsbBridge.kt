package com.shilapi.xcertplay.transport

import android.util.Base64
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque

/** Lab-only loopback transport; USB packets never traverse a LAN listener. */
class LabUsbBridge private constructor(private val port: Int, val mode: String) : Closeable {
    private class Rpc(port: Int) : Closeable {
        private val socket = Socket().apply {
            connect(InetSocketAddress("127.0.0.1", port), 3000)
            soTimeout = 7000
            tcpNoDelay = true
        }
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        @Synchronized fun call(op: String, timeout: Long = 1000, bytes: ByteArray? = null): JSONObject {
            val request = JSONObject().put("op", op).put("timeout", timeout.coerceIn(1, 5000))
            bytes?.let { request.put("data", Base64.encodeToString(it, Base64.NO_WRAP)) }
            val data = request.toString().toByteArray(Charsets.UTF_8)
            LabBridgeFraming.write(output, data)
            val reply = LabBridgeFraming.read(input)
            return JSONObject(reply.toString(Charsets.UTF_8)).also {
                if (!it.optBoolean("ok")) throw IOException(it.optString("error", "USB bridge failed"))
            }
        }
        override fun close() = socket.close()
    }
    private val channels = ArrayList<Rpc>().apply {
        try { repeat(4) { add(Rpc(port)) } }
        catch (error: Exception) { forEach { runCatching { it.close() } }; throw error }
    }
    private val readers = channels.take(2)
    private val writers = channels.drop(2)
    private fun read(index: Int, name: String, timeout: Long): ByteArray? {
        val reply = readers[index].call(name + "_read", timeout)
        return if (reply.optBoolean("timeout")) null else Base64.decode(reply.getString("data"), Base64.DEFAULT)
    }
    val mux: UsbMuxPipe = object : UsbMuxPipe {
        override fun write(data: ByteArray, timeoutMillis: Int) {
            writers[0].call("mux_write", timeoutMillis.toLong(), data)
        }
        override fun read(timeoutMillis: Long) = read(0, "mux", timeoutMillis)
        override fun close() { readers[0].close(); writers[0].close() }
    }
    val ethernet: CarPlayEthernetPipe = object : CarPlayEthernetPipe {
        override val hostMac: ByteArray? = null
        @Volatile override var linkOpenedAtNanos: Long? = null
        private val traffic = NcmTrafficCounter()
        private var sequence = 0
        private var buffered = ByteArray(0)
        private val frames = ArrayDeque<ByteArray>()
        override fun send(frame: ByteArray, timeoutMillis: Int) {
            val data = if (mode == "ncm") Ntb16Codec.build(frame, sequence++ and 0xffff) else frame
            val reply = writers[1].call("net_write", timeoutMillis.toLong(), data)
            if (!reply.optBoolean("timeout")) {
                if (linkOpenedAtNanos == null) linkOpenedAtNanos = System.nanoTime()
                traffic.sent()
            }
        }
        override fun recv(timeoutMillis: Long): ByteArray? {
            if (frames.isNotEmpty()) return frames.removeFirst().also(traffic::received)
            val chunk = read(1, "net", timeoutMillis) ?: return null
            if (mode == "ethernet") {
                if (chunk.size < 14) throw IOException("Apple Ethernet: incomplete frame")
                return chunk.also(traffic::received)
            }
            if (buffered.size + chunk.size > 131072) throw IOException("NCM bridge buffer exceeded")
            buffered += chunk
            while (buffered.isNotEmpty()) {
                if (buffered[0] == 0.toByte()) { buffered = buffered.copyOfRange(1, buffered.size); continue }
                if (buffered.size < 12) break
                if (!buffered.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4e, 0x43, 0x4d, 0x48))) {
                    throw IOException("NCM bridge: invalid transfer header")
                }
                val length = (buffered[8].toInt() and 255) or ((buffered[9].toInt() and 255) shl 8)
                if (length < 12) throw IOException("NCM bridge: invalid transfer length")
                if (buffered.size < length) break
                frames.addAll(Ntb16Codec.parse(buffered, 0, length))
                buffered = buffered.copyOfRange(length, buffered.size)
            }
            return if (frames.isEmpty()) null else frames.removeFirst().also(traffic::received)
        }
        override fun trafficSummary(airPlayPort: Int?) = traffic.summary(airPlayPort)
        override fun connectionAttemptSince(port: Int, sinceNanos: Long) = traffic.connectionAttemptSince(port, sinceNanos)
        override fun close() { readers[1].close(); writers[1].close() }
    }
    override fun close() { (readers + writers).forEach { runCatching { it.close() } } }
    companion object {
        fun open(port: Int): LabUsbBridge {
            require(port in 1024..65535)
            val mode = Rpc(port).use { it.call("open").getString("mode") }
            require(mode == "ncm" || mode == "ethernet")
            return LabUsbBridge(port, mode)
        }
    }
}
