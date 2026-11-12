package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "CarPlayVideo Settings App" data stream (type 130, [VideoInCar.SETTINGS_CHANNEL_UUID]) the iPhone
 * opens once video in car is declared; it tears the session down when this stream is refused. Framing
 * as the iAP tunnel: ChaCha20-Poly1305 frames with a 2-byte little-endian length, carrying packages
 * with a 32-byte header (size, 12-byte type such as "sync"/"rply", 4-byte command, 8-byte sequence
 * number) and a body. Each "sync" is answered with an empty "rply", as AirPlay data streams expect;
 * the bodies are not needed to play.
 *
 * The CarPlay Ultra experiment uses the same channel for data streams Tarmac does not know yet, with
 * [logPackages] on, so each package the iPhone sends is logged ([CarPlayUltra.describePackage]), and for
 * the vehicle-state streams, which hand each package body to [onMessage] and answer with [send].
 */
class VideoSettingsChannel(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val expectedPeer: InetAddress,
    private val label: String = "video settings",
    private val logPackages: Boolean = false,
    private val onMessage: ((ByteArray) -> Unit)? = null,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private var readCounter = 0L
    private var writeCounter = 0L
    private val writeLock = Any()
    private var packagesSeen = 0

    fun listen(bindAddress: InetAddress): Int {
        val bound = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }
        server = bound
        Thread({ accept(bound) }, "diplay-data-stream").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { log("$label channel rejected sender outside the control session") }
            socket = accepted
            if (closed.get()) { accepted.close(); return }
            log("$label channel connected")
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) log("$label channel ended: ${error.message}")
        } finally {
            close()
        }
    }

    private fun run(sock: Socket) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()
        val buffer = ByteArray(16 * 1024)
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        while (!closed.get()) {
            val read = input.read(buffer)
            if (read < 0) return
            ciphertext += buffer.copyOf(read)
            var offset = 0
            while (ciphertext.size - offset >= 2) {
                val length = (ciphertext[offset].toInt() and 0xff) or ((ciphertext[offset + 1].toInt() and 0xff) shl 8)
                val frame = 2 + length + TAG
                if (ciphertext.size - offset < frame) break
                val aad = ciphertext.copyOfRange(offset, offset + 2)
                plaintext += AirPlayCrypto.chachaOpen(
                    readKey, AirPlayCrypto.nonce64(readCounter++), ciphertext.copyOfRange(offset + 2, offset + frame), aad,
                )
                offset += frame
            }
            ciphertext = ciphertext.copyOfRange(offset, ciphertext.size)
            plaintext = handlePackages(plaintext, output)
        }
    }

    private fun handlePackages(buffer: ByteArray, output: OutputStream): ByteArray {
        var offset = 0
        while (buffer.size - offset >= HEADER) {
            val size = u32(buffer, offset)
            if (size < HEADER || size > MAX_PACKAGE) throw IllegalStateException("bad package size $size")
            if (buffer.size - offset < size) break
            val type = ascii(buffer, offset + 4, 12)
            if (logPackages) logPackage(type, buffer, offset, size)
            if (type == "sync") reply(output, buffer.copyOfRange(offset + 20, offset + 28))
            if (type != "rply") onMessage?.invoke(buffer.copyOfRange(offset + HEADER, offset + size))
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun logPackage(type: String, buffer: ByteArray, offset: Int, size: Int) {
        val seen = ++packagesSeen
        if (seen > CarPlayUltra.LOGGED_PACKAGES && seen % CarPlayUltra.LOG_EVERY != 0) return
        val command = u32(buffer, offset + 16)
        val sequence = (u32(buffer, offset + 20).toLong() shl 32) or (u32(buffer, offset + 24).toLong() and 0xffffffffL)
        log("$label rx #$seen " + CarPlayUltra.describePackage(type, command, sequence, buffer.copyOfRange(offset + HEADER, offset + size)))
    }

    private fun reply(output: OutputStream, seq: ByteArray) {
        val header = ByteArray(HEADER)
        header[3] = HEADER.toByte()
        "rply".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        seq.copyInto(header, 20)
        seal(output, header)
    }

    /** Sends [body] to the iPhone as an "asyn" package of type "cmnd"; dropped while nothing is connected. */
    fun send(body: ByteArray) {
        val output = socket?.takeIf { !closed.get() }?.getOutputStream() ?: return log("$label send dropped: not connected")
        val size = HEADER + body.size
        val header = ByteArray(HEADER)
        for (i in 0 until 4) header[i] = (size ushr (24 - 8 * i)).toByte()
        "asyn".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        "cmnd".toByteArray(Charsets.US_ASCII).copyInto(header, 16)
        runCatching { seal(output, header + body) }.onFailure { log("$label send failed: ${it.message}") }
    }

    // ChaCha20-Poly1305 frames of at most FRAME bytes, each with its 2-byte length as associated data.
    private fun seal(output: OutputStream, plain: ByteArray) = synchronized(writeLock) {
        var offset = 0
        while (offset < plain.size) {
            val chunk = plain.copyOfRange(offset, minOf(plain.size, offset + FRAME))
            val aad = byteArrayOf((chunk.size and 0xff).toByte(), (chunk.size shr 8).toByte())
            output.write(aad + AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter++), chunk, aad))
            offset += chunk.size
        }
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    private fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun ascii(b: ByteArray, o: Int, n: Int) =
        String(b.copyOfRange(o, o + n), Charsets.US_ASCII).trimEnd('\u0000')

    private companion object {
        const val HEADER = 32
        const val FRAME = 1024
        const val TAG = 16
        const val MAX_PACKAGE = 8 * 1024 * 1024
    }
}
