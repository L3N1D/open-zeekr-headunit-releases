package com.lenid.zoh.modules

import androidx.test.platform.app.InstrumentationRegistry
import com.lenid.zoh.aap.HandshakeMessagePolicy
import com.lenid.zoh.dongle.DongleConfig
import com.lenid.zoh.dongle.DongleSession
import com.lenid.zoh.dongle.DongleTransport
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

class StartupProtocolTest {
    @Test fun builtInConnectionPrograms() {
        CodeModules.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(listOf(0, 1, 0, 2, 0, 1, 0, 3), (0..7).map { ModuleFunction.CONNECT_ACTION.evaluate(it) })
        assertTrue(HandshakeMessagePolicy.isTerminalResponse(byteArrayOf(0, 0, 0, 0, -1, -1)))
        assertTrue(HandshakeMessagePolicy.isLateVersionResponse(byteArrayOf(0, 0, 0, 0, 0, 2)))
        assertFalse(HandshakeMessagePolicy.isTerminalResponse(byteArrayOf(1, 0, 0, 0, 0, 15)))
        assertEquals((0..11).toList(), (0..11).map { ModuleFunction.OPEN_STEP.evaluate(it) })

        assertEquals(0, ModuleFunction.READ_ERROR.evaluate(-1, 1, 0))
        assertEquals(1, ModuleFunction.READ_ERROR.evaluate(-1, 0, 0))
        assertEquals(0, ModuleFunction.READ_ERROR.evaluate(0, 1, 1))
        assertEquals(2, ModuleFunction.VERSION_FAILURE.evaluate(0, 1))
        assertEquals(1, ModuleFunction.VERSION_FAILURE.evaluate(0, 0))
        assertEquals(0, ModuleFunction.VERSION_FAILURE.evaluate(1, 0))
        assertEquals(240, ModuleFunction.WINDOW_HEIGHT.evaluate(800))
        assertEquals(812, ModuleFunction.WINDOW_HEIGHT.evaluate(64))
        assertEquals(listOf(126, 127, 87, 88), (41..44).map { ModuleFunction.MEDIA_KEY.evaluate(it) })
        assertEquals(126, ModuleFunction.MEDIA_KEY.evaluate(1))
        assertEquals(0, ModuleFunction.MEDIA_KEY.evaluate(17))
        assertEquals(118, ModuleFunction.WIDGET_ROOM.evaluate(300, 100, 70, 6))
        assertEquals(90, ModuleFunction.WIDGET_HEIGHT.evaluate(110, 40, 90, 160))
        assertEquals(40, ModuleFunction.WIDGET_HEIGHT.evaluate(110, 40, 90, 6))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 0), (2..8).map { ModuleFunction.PCM_FORMAT.evaluate(it) })

        val packets = mutableListOf<ByteBuffer>()
        val closed = CountDownLatch(1)
        val link = object : DongleTransport {
            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                closed.await()
                return -1
            }
            override fun write(buf: ByteArray, off: Int, len: Int): Boolean {
                packets.add(ByteBuffer.wrap(buf.copyOfRange(off, off + len)).order(ByteOrder.LITTLE_ENDIAN))
                return true
            }
            override fun close() = closed.countDown()
        }
        val session = DongleSession(link, { _, _ -> }, {})
        try {
            session.start(DongleConfig(1275, 876, 60, 180, night = true))
        } finally {
            session.close()
        }
        assertEquals(listOf(0x99, 1, 0x99, 0x99, 0x99, 0x99, 0x19, 8, 8, 8, 8, 8), packets.map { it.getInt(8) })
        assertEquals(listOf(1000, 25, 7, 23, 1002), packets.takeLast(5).map { it.getInt(16) })
        assertEquals(listOf(1275, 876, 60), listOf(16, 20, 24).map { packets[1].getInt(it) })
    }
}
