package com.lenid.carlink

import android.content.Context
import android.content.SharedPreferences

/** User settings sent to the dongle in the handshake (what AutoKit hides behind its password menu). */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("carlink", Context.MODE_PRIVATE)

    /** "auto" or "WxH". */
    var resolution by str("resolution", "auto")
    var fps by int("fps", 30)
    /** 0 = from the screen density. */
    var dpi by int("dpi", 0)
    /** 0 = follow the system, 1 = day, 2 = night. */
    var nightMode by int("night", 0)
    var rightHandDrive by bool("rhd", false)
    var wifi5g by bool("wifi5g", true)
    var dongleMic by bool("dongle_mic", false)
    var bluetoothAudio by bool("bt_audio", false)
    var mediaDelay by int("media_delay", 300)
    var dongleName by str("dongle_name", "Zeekr")

    /** Debug: talk to tools/dongle_bridge.py over TCP instead of USB (emulator: host is 10.0.2.2). */
    var debugBridge by bool("debug_bridge", false)
    var bridgeHost by str("bridge_host", "10.0.2.2")

    private fun str(key: String, def: String) = Pref({ p.getString(key, def)!! }, { p.edit().putString(key, it).apply() })
    private fun int(key: String, def: Int) = Pref({ p.getInt(key, def) }, { p.edit().putInt(key, it).apply() })
    private fun bool(key: String, def: Boolean) = Pref({ p.getBoolean(key, def) }, { p.edit().putBoolean(key, it).apply() })

    class Pref<T>(private val get: () -> T, private val set: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = set(value)
    }
}
