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
}
