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
    private var wheel: ((Boolean) -> Unit)? = null,
    private val send: (ClusterCall?) -> Boolean
) {
    constructor(context: Context, onWheel: (Boolean) -> Unit) : this(context, lazy { Handler(Looper.getMainLooper()) }) {
        wheel = onWheel
    }
    private constructor(context: Context, main: Lazy<Handler>) : this(
        SystemClock::elapsedRealtime,
        { task, delay -> main.value.postDelayed(task, delay) },
        { task -> if (main.isInitialized()) main.value.removeCallbacks(task) },
        send = { call -> publish(context, call) }
    ) {
        appContext = context.applicationContext
        hudPublisher = HudCallPublisher(context.applicationContext ?: context)
        if (supported(context)) listen()
    }
    private var sdk: ClusterPhoneSdk? = null
    private var triedSdk = false
    private var pendingWheel: Runnable? = null
    private val wheelCallback = ::onWheelCommand
    internal fun onWheelCommand(method: String, number: String) {
        val call = displayed ?: return
        if (method == "onCallInfoUpdateRequired") {
            publishDisplay(call)
            return
        }
        if (pendingWheel != null || (number.isNotBlank() && !sameCaller(call, number))) return
        if (method != "onAnswerCall" && method != "onEndCall") return
        val answer = method == "onAnswerCall"
        if (answer && call.state != ClusterCallState.RINGING) return
        // Let the stock Bluetooth phone act first. Forward only if the same call is still waiting.
        val task = Runnable {
            val current = displayed
            if (current != null && current.number == call.number && current.state == call.state) {
                wheel?.invoke(answer)
            }
            pendingWheel = null
        }
        pendingWheel = task
        schedule(task, 750)
    }

    private fun publishDisplay(call: ClusterCall?): Boolean {
        val ctx = appContext
        if (!triedSdk && ctx != null) {
            triedSdk = true
            sdk = ClusterPhoneSdk.acquire(ctx)
            sdk?.listener = wheelCallback
        }
        val adapter = sdk ?: return send(call)
        return try {
            adapter.publish(call)
            true
        } catch (e: Exception) {
            AppLog.w("Cluster phone update failed: " + (e.cause ?: e).javaClass.simpleName)
            send(call)
        }
    }

    private var hudPublisher: HudCallPublisher? = null
    private var appContext: Context? = null
    private var registered = false
    private var aa: ClusterCall? = null
    private val handsFreeCalls = mutableMapOf<String, ClusterCall>()
    private var incomingNumber: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                @Suppress("DEPRECATION")
                val call = intent.getParcelableExtra<android.os.Parcelable>("android.bluetooth.headsetclient.extra.CALL") ?: return
                val type = call.javaClass
                val state = type.getMethod("getState").invoke(call) as Int
                val number = type.getMethod("getNumber").invoke(call) as? String ?: ""
                val id = type.getMethod("getId").invoke(call) as Int
                onHandsFreeState(number, state, id.toString())
            } catch (e: Exception) { AppLog.w("Cluster call HFP unavailable: " + e.javaClass.simpleName) }
        }
    }

    fun onHandsFreeState(number: String, state: Int, id: String = number) {
        val phase = when (state) {
            0 -> ClusterCallState.ACTIVE
            1, 6 -> ClusterCallState.HELD
            2, 3 -> ClusterCallState.DIALING
            4, 5 -> ClusterCallState.RINGING
            7 -> null
            else -> return
        }
        val previous = handsFreeCalls[id]
        val contact = number.ifBlank { previous?.number.orEmpty() }
        if (phase == null) {
            handsFreeCalls.remove(id)
            if (sameCaller(aa, contact)) aa = null
        } else {
            handsFreeCalls[id] = ClusterCall(contact, contact, phase, 0)
        }
        renderCurrent()
    }

    private fun sameCaller(call: ClusterCall?, number: String): Boolean {
        val digits = number.filter(Char::isDigit)
        return digits.isNotEmpty() && call?.number?.filter(Char::isDigit) == digits
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
        pendingWheel?.let(cancel)
        pendingWheel = null
        wheel = null
        handsFreeCalls.clear()
        update(null)
        if (sdk?.listener === wheelCallback) sdk?.listener = null
        appContext = null
        hudPublisher = null
    }

    private var displayed: ClusterCall? = null
    private var active: ClusterCall? = null
    private var startedMs = 0L
    private val tick = Runnable { renderActive() }

    // AA may send only ACTIVE with duration zero, followed by IDLE at hangup.
    // Both protocol updates and ticks run on the same presentation (main) thread.
    fun update(next: ClusterCall?) {
        if (next == null && aa != null) {
            handsFreeCalls.entries.removeAll { sameCaller(aa, it.value.number) }
        }
        aa = next
        renderCurrent()
    }

    private fun renderCurrent() {
        val phone = aa
        val handsFree = handsFreeCalls.values.minByOrNull { it.state.ordinal }
        val matched = handsFreeCalls.values.firstOrNull { sameCaller(phone, it.number) }
        val next = when {
            phone == null -> handsFree
            handsFree?.state == ClusterCallState.RINGING && phone.state != ClusterCallState.RINGING &&
                !sameCaller(phone, handsFree.number) -> handsFree
            phone.state == ClusterCallState.ACTIVE && phone.durationMs > 0 &&
                matched?.state in listOf(ClusterCallState.RINGING, ClusterCallState.DIALING) -> phone
            matched != null -> phone.copy(state = matched.state)
            else -> phone
        }
        cancel(tick)
        if (next == null) {
            incomingNumber = null
        } else if (next.state == ClusterCallState.RINGING) incomingNumber = next.number
        if (next == null || next.state != ClusterCallState.ACTIVE) {
            active = null
            deliver(next)
            return
        }
        val previous = active
        val sameActive = previous != null && previous.number == next.number
        val hfpAnswered = phone == null || matched?.state == ClusterCallState.ACTIVE
        if (!sameActive && !hfpAnswered && incomingNumber != next.number && next.durationMs == 0L) {
            // AA also calls an outgoing, still-alerting line InCall. Wait for evidence of answer.
            active = null
            deliver(next.copy(state = ClusterCallState.DIALING))
            return
        }
        val reportedStart = nowMs() - next.durationMs
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
        if (displayed?.state != next?.state || displayed?.number != next?.number) {
            pendingWheel?.let(cancel)
            pendingWheel = null
        }
        hudPublisher?.update(next)
        if (next == displayed) return
        if (publishDisplay(next)) {
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
