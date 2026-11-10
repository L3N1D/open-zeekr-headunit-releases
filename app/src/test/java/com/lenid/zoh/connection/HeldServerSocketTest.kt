package com.lenid.zoh.connection

import java.net.Socket
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldServerSocketTest {
    private val endpoint = "192.168.1.5:5277"
    private val socket = Socket()

    @After fun tearDown() { HeldServerSocket.settle(socket) }

    @Test fun onlyOneTakerGetsTheSocket() {
        HeldServerSocket.hold(endpoint, socket)
        assertSame(socket, HeldServerSocket.take(endpoint))
        assertNull(HeldServerSocket.take(endpoint))
        assertTrue(HeldServerSocket.isTaken(endpoint))
        assertFalse(HeldServerSocket.isHeld(endpoint))
    }

    @Test fun anotherEndpointLeavesItHeld() {
        HeldServerSocket.hold(endpoint, socket)
        assertNull(HeldServerSocket.take("192.168.1.6:5277"))
        assertTrue(HeldServerSocket.isHeld(endpoint))
    }

    @Test fun aTakenSocketIsNotDiscarded() {
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.take(endpoint)
        assertFalse(HeldServerSocket.discard(socket))
    }

    @Test fun aDiscardAfterATakeLeavesTheTakerItsSocket() {
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.take(endpoint)
        HeldServerSocket.discard(socket)
        assertTrue(HeldServerSocket.isTaken(endpoint))
        assertFalse(socket.isClosed)
    }

    @Test fun anUntakenSocketIsDiscarded() {
        HeldServerSocket.hold(endpoint, socket)
        assertTrue(HeldServerSocket.discard(socket))
        assertFalse(HeldServerSocket.isHeld(endpoint))
    }

    @Test fun settleEndsTheHandOff() {
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.take(endpoint)
        HeldServerSocket.settle(socket)
        assertFalse(HeldServerSocket.isTaken(endpoint))
    }

    @Test fun twoEndpointsAreHeldApart() {
        val other = Socket()
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.hold("192.168.1.6:5277", other)
        assertSame(socket, HeldServerSocket.take(endpoint))
        assertSame(other, HeldServerSocket.take("192.168.1.6:5277"))
        HeldServerSocket.settle(other)
    }

    @Test fun anAbandonedTakeIsNoLongerJoined() {
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.take(endpoint)
        HeldServerSocket.abandon(socket)
        assertFalse(HeldServerSocket.isTaken(endpoint))
        assertTrue(socket.isClosed)
    }

    @Test fun discardAllLeavesATakenSocket() {
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.take(endpoint)
        HeldServerSocket.discardAll()
        assertTrue(HeldServerSocket.isTaken(endpoint))
        assertFalse(socket.isClosed)
    }
}
