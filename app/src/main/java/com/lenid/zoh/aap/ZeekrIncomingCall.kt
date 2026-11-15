package com.lenid.zoh.aap

import android.content.Context
import com.lenid.zoh.aap.protocol.proto.Control
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.aap.protocol.proto.Control.Service.PhoneStatus_State
import com.lenid.zoh.utils.protoUint32ToLong
import org.json.JSONObject

internal enum class ClusterCallState(val zeekrStatus: Int) {
    RINGING(12), ACTIVE(16), HELD(17);

    companion object {
        fun from(call: Control.Service.PhoneStatus_Call): ClusterCallState? {
            if (!call.hasState()) return null
            return when (call.state) {
                PhoneStatus_State.Incoming -> RINGING
                PhoneStatus_State.InCall, PhoneStatus_State.Muted, PhoneStatus_State.Conferenced -> ACTIVE
                PhoneStatus_State.OnHold -> HELD
                else -> null
            }
        }
    }
}

internal data class ClusterCall(val name: String, val number: String, val state: ClusterCallState, val durationMs: Long) {
    companion object {
        fun from(status: Control.Service.PhoneStatusService): ClusterCall? {
            // Show a waiting caller first, then resume the active conversation ahead of a held call.
            val calls = status.callsList.mapNotNull { call -> ClusterCallState.from(call)?.let { it to call } }
            val (state, call) = calls.minByOrNull { it.first.ordinal } ?: return null
            val number = call.callerNumber.trim().take(96)
            return ClusterCall(call.callerId.trim().take(192).ifEmpty { number }, number, state,
                call.callDurationSeconds.protoUint32ToLong() * 1000L)
        }
    }
}

internal class ZeekrIncomingCall(private val context: Context) {
    private var displayed: ClusterCall? = null

    // Serialized by the session presentation queue. Never send an idle event for an
    // unrelated call when no projected caller has been shown.
    fun update(next: ClusterCall?) {
        if (next == displayed) return
        try {
            val manager = context.getSystemService("systemhmi") ?: return
            val payload = JSONObject().apply {
                val count = if (next == null) 0 else 1
                put("CallId", count) // Capitalization matches the firmware CallInfoDTO.
                put("activeCallId", count)
                put("callCount", count)
                put("callStatus", next?.state?.zeekrStatus ?: 0)
                put("callDuration", next?.durationMs ?: 0L)
                put("index", 0)
                put("isHandFree", next != null)
                put("isMicOnVehicleMuted", false)
                put("isRingtoneMuted", false)
                put("contactName", next?.name)
                put("contactNumber", next?.number)
            }
            // The stock SDK additionally updates AudioManager mute. Only publish its display event.
            val event = JSONObject().put("domain", "Phone")
                .put("id", "PHONE_UPDATE_CALL_INFO_NOTIFY").put("payload", payload)
            manager.javaClass.getMethod("set", String::class.java).invoke(manager, event.toString())
            displayed = next
        } catch (e: Exception) {
            AppLog.w("Zeekr call display unavailable: " + (e.cause ?: e).javaClass.simpleName)
        }
    }

    companion object {
        fun supported(context: Context): Boolean = try {
            context.getSystemService("systemhmi")?.javaClass?.getMethod("set", String::class.java) != null
        } catch (_: Exception) { false }
    }
}
