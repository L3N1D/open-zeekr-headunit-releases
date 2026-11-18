package com.lenid.zoh.aap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lenid.zoh.utils.AppLog

/** Main-thread companion feed. A lease lets HUD Map clear the card if this process disappears. */
internal class HudCallPublisher(
    private val nowMs: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val send: (Frame) -> Unit
) {
    data class Frame(val state: String, val name: String, val number: String, val durationMs: Long, val sentAt: Long)

    constructor(context: Context) : this(context, lazy { Handler(Looper.getMainLooper()) })
    private constructor(context: Context, handler: Lazy<Handler>) : this(
        SystemClock::elapsedRealtime,
        { task, delay -> handler.value.postDelayed(task, delay) },
        { task -> if (handler.isInitialized()) handler.value.removeCallbacks(task) },
        { frame ->
            try {
                context.sendBroadcast(Intent("com.lenid.ohroutemap.CALL_STATE")
                    .setComponent(ComponentName("com.lenid.ohroutemap", "com.lenid.ohroutemap.CallReceiver"))
                    .putExtra("protocol_version", 1)
                    .putExtra("state", frame.state)
                    .putExtra("name", frame.name)
                    .putExtra("number", frame.number)
                    .putExtra("duration_ms", frame.durationMs)
                    .putExtra("sent_at", frame.sentAt))
            } catch (e: RuntimeException) {
                AppLog.w("HUD call delivery unavailable: " + e.javaClass.simpleName)
            }
        }
    )

    private var current: ClusterCall? = null
    private var changedAt = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (current == null) return
            publish()
            schedule(this, 5_000)
        }
    }

    fun update(call: ClusterCall?) {
        if (current == call) return
        val wasIdle = current == null
        current = call
        changedAt = nowMs()
        publish()
        if (call == null) cancel(heartbeat)
        else if (wasIdle) schedule(heartbeat, 5_000)
    }

    private fun publish() {
        val now = nowMs()
        val call = current
        val duration = (call?.durationMs ?: 0L) +
            if (call?.state == ClusterCallState.ACTIVE) (now - changedAt).coerceAtLeast(0) else 0L
        send(Frame(call?.state?.name ?: "IDLE", call?.name.orEmpty(), call?.number.orEmpty(), duration, now))
    }
}
