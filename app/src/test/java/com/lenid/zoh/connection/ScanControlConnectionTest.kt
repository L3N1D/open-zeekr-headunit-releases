package com.lenid.zoh.connection

import android.content.Context
import com.lenid.zoh.connection.projection.SocketProjectionConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.InetAddress
import java.net.Socket

class ScanControlConnectionTest {
    private fun set(manager: CommManager, name: String, value: Any?) =
        CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)

    private fun manager(socket: Socket): CommManager = mock(CommManager::class.java, CALLS_REAL_METHODS).also {
        set(it, "transportLifecycleLock", Any())
        set(it, "_connection", SocketProjectionConnection(socket, mock(Context::class.java)))
        set(it, "connectionAttempt", Any())
        set(it, "physicalConnectionReached", true)
    }

    private fun socket(host: String) = object : Socket() {
        override fun getInetAddress() = InetAddress.getByName(host)
        override fun getPort() = 5277
    }

    @Test fun `outgoing IP sockets never authorize Native scan control`() {
        socket("192.0.2.1").use { socket ->
            val manager = manager(socket)
            assertNotNull(manager.acceptedWirelessSession)
            set(manager, "outgoingEndpoint", "192.0.2.1" to 5277)
            assertNull(manager.acceptedWirelessSession)
        }
    }


}
