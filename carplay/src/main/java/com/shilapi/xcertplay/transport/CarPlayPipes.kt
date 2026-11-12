package com.shilapi.xcertplay.transport

import java.io.Closeable

/** Projection-only boundaries; native USB remains the default implementation. */
interface UsbMuxPipe : Closeable {
    fun write(data: ByteArray, timeoutMillis: Int)
    fun read(timeoutMillis: Long): ByteArray?
}
interface CarPlayEthernetPipe : Closeable {
    val hostMac: ByteArray?
    val linkOpenedAtNanos: Long?
    fun send(frame: ByteArray, timeoutMillis: Int)
    fun recv(timeoutMillis: Long): ByteArray?
    fun trafficSummary(airPlayPort: Int?): String
    fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean
}
