package com.lenid.zoh.dongle

import com.shilapi.xcertplay.transport.TransportIo

import com.lenid.zoh.modules.TransportModules

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

/** Raw byte pipe to the dongle: USB on the head unit, TCP to carlink/tools/dongle_bridge.py when debugging. */
interface DongleTransport {
    /** Blocks until some bytes arrive; returns the count, or -1 when the link is gone. */
    fun read(buf: ByteArray, off: Int, len: Int): Int
    /** Returns false when the link is gone. */
    fun write(buf: ByteArray, off: Int, len: Int): Boolean
    fun close()
}

class DongleUsbTransport(usb: UsbManager, device: UsbDevice) : DongleTransport {
    private val io = TransportModules.autoKit
    private val iface = device.getInterface(0)
    private lateinit var epIn: UsbEndpoint
    private lateinit var epOut: UsbEndpoint
    private lateinit var conn: UsbDeviceConnection

    init {
        io.open(TransportIo.Path.USB, selectEndpoint = {
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
        }, configure = {
            if (!usb.hasPermission(device)) throw IOException("USB permission missing")
        }, acquire = {
            val opened = io.acquireUsb { usb.openDevice(device) } ?: throw IOException("openDevice failed")
            try {
                if (!opened.claimInterface(iface, true)) throw IOException("claimInterface failed")
                conn = opened
            } catch (error: Throwable) {
                opened.close()
                throw error
            }
        })
    }

    override fun read(buf: ByteArray, off: Int, len: Int) =
        io.transfer(TransportIo.Mode.USB_CAPPED, len, epIn.maxPacketSize) { pos, size -> conn.bulkTransfer(epIn, buf, off + pos, size, 0) }

    override fun write(buf: ByteArray, off: Int, len: Int): Boolean =
        len == 0 || io.transfer(TransportIo.Mode.FULL, len, epOut.maxPacketSize) { pos, size ->
            conn.bulkTransfer(epOut, buf, off + pos, size, 1000)
        } == len

    override fun close() {
        try { conn.releaseInterface(iface); conn.close() } catch (_: Exception) {}
    }
}

class DongleTcpTransport(host: String, port: Int) : DongleTransport {
    private val io = TransportModules.autoKit
    private val socket = Socket().apply {
        try {
            lateinit var peer: InetSocketAddress
            io.open(TransportIo.Path.TCP, { peer = InetSocketAddress(host, port) }, { tcpNoDelay = true }, { connect(peer, 2000) })
        } catch (error: Throwable) {
            close()
            throw error
        }
    }
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    override fun read(buf: ByteArray, off: Int, len: Int) =
        try { io.read(input, buf, off, len) } catch (_: IOException) { -1 }

    override fun write(buf: ByteArray, off: Int, len: Int) =
        try { io.write(output, buf, off, len); output.flush(); true } catch (_: IOException) { false }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
