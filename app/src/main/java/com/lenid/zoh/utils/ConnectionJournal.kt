package com.lenid.zoh.utils

import android.content.Context
import com.lenid.zoh.App
import com.lenid.zoh.connection.CommManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, local state history: no names, phone addresses or raw exception messages. */
object ConnectionJournal {
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            com.lenid.zoh.dongle.DongleService.state.map { it.active }.distinctUntilChanged().collect {
                record(app, if(it) "dongle:active" else "dongle:inactive")
            }
        }
        scope.launch {
            App.provide(app).commManager.connectionState.map { when(it) {
                is CommManager.ConnectionState.Disconnected -> "Disconnected"
                CommManager.ConnectionState.Connecting -> "Connecting"
                CommManager.ConnectionState.Connected -> "Connected"
                CommManager.ConnectionState.StartingTransport -> "StartingTransport"
                CommManager.ConnectionState.HandshakeComplete -> "HandshakeComplete"
                CommManager.ConnectionState.TransportStarted -> "TransportStarted"
                is CommManager.ConnectionState.Error -> "Error"
            } }.distinctUntilChanged().collect {
                record(app, "AA:$it")
            }
        }
    }
    @Synchronized fun record(context: Context, event: String) {
        runCatching {
            val p = context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)
            val old = JSONArray(p.getString("events", "[]"))
            val next = JSONArray()
            for (i in maxOf(0, old.length() - 199) until old.length()) next.put(old.getJSONObject(i))
            next.put(JSONObject().put("time", System.currentTimeMillis()).put("event", event.take(160)))
            p.edit().putString("events", next.toString()).apply()
        }
    }
    fun entries(context: Context): List<Pair<Long, String>> = runCatching {
        val array = JSONArray(context.getSharedPreferences("connection_history", Context.MODE_PRIVATE).getString("events", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { row -> row.getLong("time") to row.getString("event") } }.reversed()
    }.getOrDefault(emptyList())
    fun clear(context: Context) { context.getSharedPreferences("connection_history", Context.MODE_PRIVATE).edit().clear().apply() }
}
