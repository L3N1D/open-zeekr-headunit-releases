package com.lenid.zoh.connection.wifi.modes.nativeaa

import android.bluetooth.BluetoothSocket
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * One phone-side connection the wireless handshake can run over.
 *
 * The handshake needs surprisingly little from its transport: two streams, a way to close them, and
 * enough identity to write a useful log line. Everything that makes the exchange what it is —
 * `WppFraming`, `WppHandshakeSession`, the reader coroutine, the stage deadlines — is already
 * written against `InputStream`/`OutputStream` and never mentions Bluetooth. Naming that surface
 * here is what lets a second transport exist without any of it changing.
 */
interface HandshakeLink : Closeable {

    /** Bytes from the phone. */
    val input: InputStream

    /** Bytes to the phone. */
    val output: OutputStream

    /** The phone's name, when the transport knows it. Logging only. */
    val peerName: String?

    /**
     * The phone's Bluetooth address as canonical `AA:BB:CC:DD:EE:FF`, or null.
     *
     * Canonical because every consumer compares it with addresses from `android.bluetooth`, and the
     * module publishes its own without separators. See [persistPeerForAutoStart].
     */
    val peerAddress: String?

    /** Which radio or channel carried this, for logs that have to tell several apart. */
    val radioLabel: String?
}

/** The ordinary route: a phone that connected to one of this unit's own RFCOMM listeners. */
class BluetoothSocketLink(
    private val socket: BluetoothSocket,
    override val radioLabel: String? = null
) : HandshakeLink {

    override val input: InputStream by lazy { socket.inputStream }
    override val output: OutputStream by lazy { socket.outputStream }
    override val peerName: String? get() = runCatching { socket.remoteDevice?.name }.getOrNull()
    override val peerAddress: String? get() = runCatching { socket.remoteDevice?.address }.getOrNull()
    override fun close() {
        socket.close()
    }
}
