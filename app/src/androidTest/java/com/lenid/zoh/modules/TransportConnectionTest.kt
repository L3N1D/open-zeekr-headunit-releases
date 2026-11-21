package com.lenid.zoh.modules

import androidx.test.platform.app.InstrumentationRegistry
import com.lenid.zoh.connection.projection.SocketProjectionConnection
import com.lenid.zoh.dongle.DongleTcpTransport
import com.shilapi.xcertplay.transport.TransportIo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket

class TransportConnectionTest {
    @Test fun productionAdaptersOverLoopback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CodeModules.initialize(context)
        val address = InetAddress.getByName("127.0.0.1")
        val payload = byteArrayOf(2, 4, 6, 8)
        ServerSocket(0, 1, address).use { server ->
            server.soTimeout = 3000
            val aa = SocketProjectionConnection("127.0.0.1", server.localPort, context)
            try {
                assertTrue(runBlocking { aa.connect() })
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val instrument = InstrumentationRegistry.getInstrumentation()
                    lateinit var host: android.widget.FrameLayout
                    lateinit var motion: com.lenid.zoh.view.ProjectionResizeMotion
                    instrument.runOnMainSync {
                        host = android.widget.FrameLayout(context)
                        motion = com.lenid.zoh.view.ProjectionResizeMotion(host) { true }
                    }
                    try {
                        for (width in listOf(1275, 1796, 1275)) {
                            instrument.runOnMainSync { host.layout(0, 0, width, 876) }
                            assertTrue(aa.isConnected)
                            assertEquals(4, aa.sendBlocking(payload, 4, 3000))
                            val echo = ByteArray(4)
                            DataInputStream(peer.getInputStream()).readFully(echo)
                            assertArrayEquals(payload, echo)
                            peer.getOutputStream().write(echo)
                            echo.fill(0)
                            assertEquals(4, aa.recvBlocking(echo, 4, 3000, true))
                            assertArrayEquals(payload, echo)
                        }
                    } finally { instrument.runOnMainSync { motion.dispose() } }
                }
            } finally { aa.disconnect() }
            val dongle = DongleTcpTransport("127.0.0.1", server.localPort)
            try {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    assertTrue(dongle.write(payload, 1, 2))
                    val echo = ByteArray(2)
                    DataInputStream(peer.getInputStream()).readFully(echo)
                    assertArrayEquals(byteArrayOf(4, 6), echo)
                    peer.getOutputStream().write(9)
                    assertEquals(1, dongle.read(echo, 1, 1))
                    assertEquals(9, echo[1].toInt())
                }
            } finally { dongle.close() }
        }
    }

    @Test fun usbRetriesTransfersAndCancellation() {
        CodeModules.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        var attempts = 0
        var delay = 0
        val opened = TransportModules.androidAuto.acquireUsb(pause = { delay += it }) {
            attempts++
            if (it == 3) "device" else null
        }
        assertEquals("device", opened)
        assertEquals(3, attempts)
        assertEquals(2000, delay)
        var current = true
        attempts = 0
        val cancelled = TransportModules.androidAuto.acquireUsb<String>(current = { current }, pause = { current = false }) {
            attempts++
            null
        }
        assertNull(cancelled)
        assertEquals(1, attempts)
        for (engine in listOf(TransportModules.carPlay, TransportModules.autoKit)) {
            attempts = 0
            assertNull(engine.acquireUsb<String>(pause = { error("unexpected retry") }) { attempts++; null })
            assertEquals(1, attempts)
        }
        val offsets = mutableListOf<Int>()
        val transferred = TransportModules.autoKit.transfer(TransportIo.Mode.FULL, 5) { pos, size ->
            offsets.add(pos)
            minOf(size, 2)
        }
        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(5, transferred)
        assertEquals(-1, TransportModules.autoKit.transfer(TransportIo.Mode.FULL, 5) { _, _ -> -1 })
        assertEquals(65536, TransportModules.autoKit.transfer(TransportIo.Mode.USB_CAPPED, 70000) { _, size -> size })
        assertEquals(3, TransportModules.carPlay.transfer(TransportIo.Mode.FRAME, 5) { _, _ -> 3 })
    }
}
