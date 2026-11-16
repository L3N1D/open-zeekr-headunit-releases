package com.lenid.zoh.aap

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lenid.zoh.aap.protocol.proto.Control
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.aap.protocol.proto.Control.Service.PhoneStatus_State
import com.lenid.zoh.utils.protoUint32ToLong
import org.json.JSONObject

internal enum class ClusterCallState(val zeekrStatus: Int) {
    RINGING(12), ACTIVE(16), HELD(17), DIALING(6);

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

internal class ZeekrIncomingCall(
    private val nowMs: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (ClusterCall?) -> Boolean
) {
    constructor(context: Context) : this(context, lazy { Handler(Looper.getMainLooper()) })
    private constructor(context: Context, main: Lazy<Handler>) : this(
        SystemClock::elapsedRealtime,
        { task, delay -> main.value.postDelayed(task, delay) },
        { task -> if (main.isInitialized()) main.value.removeCallbacks(task) },
        { call -> publish(context, call) }
    ) { appContext = context.applicationContext }
    private var appContext: Context? = null
    private var registered = false
    private var aa: ClusterCall? = null
    private var answeredNumber: String? = null
    private var answeredAt = 0L
    private var incomingNumber: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                @Suppress("DEPRECATION")
                val call = intent.getParcelableExtra<android.os.Parcelable>("android.bluetooth.headsetclient.extra.CALL") ?: return
                val type = call.javaClass
                val state = type.getMethod("getState").invoke(call) as Int
                val number = type.getMethod("getNumber").invoke(call) as? String ?: ""
                onHandsFreeState(number, state)
            } catch (e: Exception) { AppLog.w("Cluster call HFP unavailable: " + e.javaClass.simpleName) }
        }
    }

    fun onHandsFreeState(number: String, state: Int) {
        val key = number.filter(Char::isDigit)
        AppLog.i("Cluster call HFP state: $state hasNumber=${key.isNotEmpty()} matchesAA=${key.isNotEmpty() && key == aa?.number?.filter(Char::isDigit)}")
        if (key.isEmpty()) return
        if (state == 0 && answeredNumber != key) {
            answeredNumber = key
            answeredAt = nowMs()
        } else if (state == 7 && answeredNumber == key) {
            answeredNumber = null
        }
        if (state == 0 && aa?.number?.filter(Char::isDigit) == key) update(aa)
    }

    private fun listen() {
        val ctx = appContext ?: return
        if (registered) return
        try {
            androidx.core.content.ContextCompat.registerReceiver(ctx, receiver,
                IntentFilter("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"),
                "android.permission.BLUETOOTH_PRIVILEGED", null, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            registered = true
        } catch (e: RuntimeException) { AppLog.w("Cluster HFP listener unavailable: " + e.javaClass.simpleName) }
    }

    fun close() {
        if (registered) appContext?.unregisterReceiver(receiver)
        registered = false
        appContext = null
        update(null)
    }

    private var displayed: ClusterCall? = null
    private var active: ClusterCall? = null
    private var startedMs = 0L
    private val tick = Runnable { renderActive() }

    // AA may send only ACTIVE with duration zero, followed by IDLE at hangup.
    // Both protocol updates and ticks run on the same presentation (main) thread.
    fun update(next: ClusterCall?) {
        listen()
        aa = next
        cancel(tick)
        if (next == null) {
            answeredNumber = null
            incomingNumber = null
        } else if (next.state == ClusterCallState.RINGING) incomingNumber = next.number
        if (next == null || next.state != ClusterCallState.ACTIVE) {
            active = null
            deliver(next)
            return
        }
        val previous = active
        val sameActive = previous != null && previous.number == next.number
        val hfpAnswered = answeredNumber != null && answeredNumber == next.number.filter(Char::isDigit)
        if (!sameActive && !hfpAnswered && incomingNumber != next.number && next.durationMs == 0L) {
            // AA also calls an outgoing, still-alerting line InCall. Wait for evidence of answer.
            active = null
            deliver(next.copy(state = ClusterCallState.DIALING))
            return
        }
        val reportedStart = if (hfpAnswered && next.durationMs == 0L) answeredAt else nowMs() - next.durationMs
        startedMs = if (previous != null && previous.number == next.number &&
            (next.number.isNotBlank() || previous.name == next.name)) {
            minOf(startedMs, reportedStart)
        } else reportedStart
        active = next
        renderActive()
    }

    private fun renderActive() {
        val call = active ?: return
        val elapsed = (nowMs() - startedMs).coerceAtLeast(0)
        deliver(call.copy(durationMs = elapsed / 1000 * 1000))
        schedule(tick, 1000L - elapsed % 1000)
    }

    private fun deliver(next: ClusterCall?) {
        if (next == displayed) return
        if (send(next)) {
            if (displayed?.state != next?.state) AppLog.i("Cluster call accepted: " + (next?.state?.name ?: "IDLE"))
            displayed = next
        } else {
            AppLog.w("Cluster call delivery failed: " + (next?.state?.name ?: "IDLE"))
        }
    }

    companion object {
        private fun publish(context: Context, next: ClusterCall?): Boolean {
            val manager = context.getSystemService("systemhmi") ?: return false
            return try {
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
                manager.javaClass.getMethod("set", String::class.java).invoke(manager, event.toString()) == true
            } catch (e: Exception) {
                AppLog.w("Zeekr call display unavailable: " + (e.cause ?: e).javaClass.simpleName)
                false
            }
        }

        fun supported(context: Context): Boolean = try {
            context.getSystemService("systemhmi")?.javaClass?.getMethod("set", String::class.java) != null
        } catch (_: Exception) { false }
    }
}
