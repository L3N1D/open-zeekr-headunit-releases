package com.lenid.carlink

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Raw byte pipe to the dongle: USB on the head unit, TCP to dongle_bridge.py when debugging. */
interface Transport {
    /** Blocks until some bytes arrive; returns the count, or -1 when the link is gone. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    /** Returns false when the link is gone. */
    fun write(buf: ByteArray, off: Int, len: Int): Boolean
    fun close()
}

class UsbTransport(usb: UsbManager, device: UsbDevice) : Transport {
    private val iface: UsbInterface = device.getInterface(0)
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint
    private val conn: UsbDeviceConnection

    init {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        conn = usb.openDevice(device) ?: throw IOException("openDevice failed")
        if (!conn.claimInterface(iface, true)) { conn.close(); throw IOException("claimInterface failed") }
    }

    override fun read(buf: ByteArray, off: Int, len: Int) = conn.bulkTransfer(epIn, buf, off, minOf(len, 65536), 0)

    override fun write(buf: ByteArray, off: Int, len: Int): Boolean {
        var o = off
        while (o < off + len) {
            val n = conn.bulkTransfer(epOut, buf, o, off + len - o, 1000)
            if (n <= 0) return false
            o += n
        }
        return true
    }

    override fun close() {
        try { conn.releaseInterface(iface); conn.close() } catch (_: Exception) {}
    }
}

class TcpTransport(host: String, port: Int) : Transport {
    private val socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), 2000)
    }
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override fun read(buf: ByteArray, off: Int, len: Int) =
        try { input.read(buf, off, len) } catch (_: IOException) { -1 }

    override fun write(buf: ByteArray, off: Int, len: Int) =
        try { output.write(buf, off, len); output.flush(); true } catch (_: IOException) { false }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
