package com.lenid.zoh.aap

import com.lenid.zoh.aap.protocol.proto.Control.Service
import org.junit.Assert.*
import org.junit.Test

class IncomingCallerTest {
    private fun call(state: Service.PhoneStatus_State, name: String, number: String) =
        Service.PhoneStatus_Call.newBuilder().setState(state).setCallerId(name).setCallerNumber(number)
    @Test fun waitingCallerWinsOverActiveCallAndFallsBackToNumber() {
        val snapshot = Service.PhoneStatusService.newBuilder()
            .addCalls(call(Service.PhoneStatus_State.InCall, "Первый", "100"))
            .addCalls(call(Service.PhoneStatus_State.Incoming, " ", "200")).build()
        assertEquals(ClusterCall("200", "200", ClusterCallState.RINGING, 0L), ClusterCall.from(snapshot))
    }
    @Test fun answeredAndHeldCallsRemainVisibleUntilEnded() {
        val snapshot = Service.PhoneStatusService.newBuilder()
        val caller = call(Service.PhoneStatus_State.Incoming, "Имя", "100")
        assertEquals("Имя", ClusterCall.from(snapshot.addCalls(caller).build())?.name)
        val active = snapshot.setCalls(0, caller.setState(Service.PhoneStatus_State.InCall).setCallDurationSeconds(65)).build()
        assertEquals(ClusterCall("Имя", "100", ClusterCallState.ACTIVE, 65000L), ClusterCall.from(active))
        val held = snapshot.setCalls(0, caller.setState(Service.PhoneStatus_State.OnHold)).build()
        assertEquals(ClusterCallState.HELD, ClusterCall.from(held)?.state)
        val ended = snapshot.setCalls(0, caller.setState(Service.PhoneStatus_State.HangingUp)).build()
        assertNull(ClusterCall.from(ended))
        assertNull(ClusterCall.from(snapshot.clearCalls().build()))
    }

    @Test fun rejectedCallIsRetriedAndSuccessfulUpdatesAreDeduplicated() {
        val attempts = mutableListOf<ClusterCall?>()
        val bridge = ZeekrIncomingCall({ 0L }, { _, _ -> }, { }) { attempts.add(it); attempts.size > 1 }
        val active = ClusterCall("Test", "100", ClusterCallState.ACTIVE, 1000L)
        val elapsed = active.copy(durationMs = 10000L)
        listOf(active, active, active, elapsed, null, null).forEach(bridge::update)
        assertEquals(listOf(active, active, elapsed, null), attempts)
    }

    @Test fun handsFreeAloneShowsIncomingAnsweredTimeAndHangup() {
        var now = 1000L
        var pending: Runnable? = null
        val sent = mutableListOf<ClusterCall?>()
        val bridge = ZeekrIncomingCall({ now }, { task, _ -> pending = task }, { pending = null }) { sent.add(it) }
        bridge.onHandsFreeState("100", 4, "1")
        assertEquals(ClusterCall("100", "100", ClusterCallState.RINGING, 0), sent.last())
        bridge.update(null) // Empty AA snapshots do not suppress an HFP-only call.
        now += 5000
        bridge.onHandsFreeState("", 0, "1")
        assertEquals(0L, sent.last()?.durationMs)
        now += 3000
        pending!!.run()
        assertEquals(ClusterCall("100", "100", ClusterCallState.ACTIVE, 3000), sent.last())
        bridge.onHandsFreeState("", 7, "1")
        assertNull(sent.last())
        assertNull(pending)
    }

    @Test fun aaContactEnrichesHandsFreeCallWithoutRestartingClock() {
        var now = 5000L
        val sent = mutableListOf<ClusterCall?>()
        val bridge = ZeekrIncomingCall({ now }, { _, _ -> }, {}) { sent.add(it) }
        bridge.onHandsFreeState("100", 2)
        assertEquals(ClusterCallState.DIALING, sent.last()?.state)
        bridge.onHandsFreeState("100", 0)
        now += 2000
        bridge.update(ClusterCall("Test", "100", ClusterCallState.ACTIVE, 0))
        assertEquals(ClusterCall("Test", "100", ClusterCallState.ACTIVE, 2000), sent.last())
        bridge.close()
        assertNull(sent.last())
    }

    @Test fun steeringWheelTargetsCurrentCallerAndYieldsToNativePhone() {
        val tasks = mutableListOf<Runnable>()
        val commands = mutableListOf<Boolean>()
        val bridge = ZeekrIncomingCall({ 0L }, { task, _ -> tasks.add(task) }, { tasks.remove(it) }, { commands.add(it) }) { true }
        val ringing = ClusterCall("Contact", "100", ClusterCallState.RINGING, 0)
        bridge.update(ringing)
        bridge.onWheelCommand("onAnswerCall", "200")
        assertTrue(tasks.isEmpty())
        repeat(2) { bridge.onWheelCommand("onAnswerCall", "100") }
        tasks.removeAt(0).run()
        assertTrue(tasks.isEmpty())
        assertEquals(listOf(true), commands)
        bridge.onWheelCommand("onAnswerCall", "100")
        bridge.update(ringing.copy(state = ClusterCallState.ACTIVE))
        assertEquals(1, tasks.size) // only the duration tick survives the native answer
        bridge.onWheelCommand("onEndCall", "100")
        tasks.last().run()
        assertEquals(listOf(true, false), commands)
        bridge.close()
    }

    @Test fun silentPhoneKeepsTimeUntilHangup() {
        var now = 50000L
        var pending: Runnable? = null
        val sent = mutableListOf<ClusterCall?>()
        val bridge = ZeekrIncomingCall({ now }, { task, _ -> pending = task }, { pending = null }) { sent.add(it) }
        val call = ClusterCall("Test", "100", ClusterCallState.ACTIVE, 0L)
        bridge.update(call)
        assertEquals(ClusterCallState.DIALING, sent.last()?.state)
        assertNull(pending)
        now += 5000
        bridge.onHandsFreeState("100", 0)
        now += 3100
        pending!!.run()
        bridge.update(call) // A repeated zero must not reset elapsed time.
        now += 6900
        pending!!.run()
        val stale = pending!!
        bridge.update(null)
        stale.run()
        assertNull(pending)
        assertEquals(listOf(0L, 0L, 3000L, 10000L, null), sent.map { it?.durationMs })
    }
}
