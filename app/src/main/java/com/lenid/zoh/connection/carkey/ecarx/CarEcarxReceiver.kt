package com.lenid.zoh.connection.carkey.ecarx

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import com.lenid.zoh.connection.carkey.CarKeyReceiver
import com.lenid.zoh.utils.AppLog
import org.json.JSONObject

/**
 * Steering-wheel media buttons on ECARX head units (Zeekr, Geely, Lynk & Co).
 *
 * None of these buttons reach Android as media keys. The car sends each one to the firmware's
 * SystemHMI service as a `MEDIA_UPDATE_MEDIA_STATUS_REQUEST_CONTROL` message with a `dataType`, and
 * only the stock media centre acts on it. On a Zeekr (8155, Android 11) a press gives 5 for next,
 * 6 for previous, 7 for play and 8 for pause, the same table the media centre's own handler uses.
 * Next and previous sometimes also arrive as ordinary media keys, but only because a third-party
 * app such as GMC converts them. This subscribes to the same "Media" domain through
 * `android.app.systemHMI.SystemHMIManager`, which third-party apps on these units already use.
 * `CommManager` drops the second copy of a press that comes in both ways.
 *
 * The SystemHMI classes are firmware additions absent from the SDK, so the callback is a plain
 * [Binder] speaking the `ISystemHMICallback` AIDL protocol, with transaction codes read from the
 * firmware's own Stub. Nothing is compiled against them and the receiver stays inert elsewhere.
 */
class CarEcarxReceiver : CarKeyReceiver {

    companion object {
        private const val SERVICE = "systemhmi"
        private const val CALLBACK = "android.app.systemHMI.ISystemHMICallback"
        private const val DOMAIN = "Media"
        private const val MSG_STATUS_REQUEST = "MEDIA_UPDATE_MEDIA_STATUS_REQUEST_CONTROL"

        /**
         * The key a `dataType` stands for, or null for the media centre's other requests
         * (source lists, progress, favourites and so on).
         *
         * Play and pause both become a toggle. The car picks one from its own idea of the playback
         * state, which a pause on the phone or on the screen leaves stale, and a toggle still does
         * what the driver meant then. 12 and 13 are the media centre's alternative next and
         * previous codes.
         */
        fun keyCodeFor(dataType: Int): Int? = when (dataType) {
            5, 12 -> KeyEvent.KEYCODE_MEDIA_NEXT
            6, 13 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            7, 8 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> null
        }
    }

    private var context: Context? = null
    private var manager: Any? = null
    private var subscription: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override val isSupported: Boolean
        get() = try {
            Class.forName("$CALLBACK\$Stub")
            true
        } catch (_: Throwable) {
            false
        }

    override val isSUNeeded = false

    // Only while projecting: outside a session the stock media centre owns the button.
    override val isSessionScoped = true

    override fun register(context: Context) {
        val manager = context.getSystemService(SERVICE)
        if (manager == null) {
            AppLog.i("CarEcarxReceiver: no $SERVICE service, not listening")
            return
        }
        val callbackClass = Class.forName(CALLBACK)
        val stubClass = Class.forName("$CALLBACK\$Stub")
        fun code(name: String) = stubClass.getDeclaredField("TRANSACTION_$name")
            .apply { isAccessible = true }.getInt(null)

        val binder = MediaCallbackBinder(
            getDomainId = code("getDomainId"),
            getState = code("getState"),
            onReceive = code("onReceive"),
        ) { json -> mainHandler.post { handleMessage(json) } }
        val callback = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val subscribed = manager.javaClass.getMethod("subscribe", callbackClass).invoke(manager, callback)
        AppLog.i("CarEcarxReceiver: subscribed to SystemHMI $DOMAIN domain: $subscribed")

        this.context = context
        this.manager = manager
        this.subscription = callback
    }

    override fun unregister() {
        val manager = manager
        val subscription = subscription
        if (manager != null && subscription != null) {
            try {
                manager.javaClass.getMethod("unsubscribe", Class.forName(CALLBACK)).invoke(manager, subscription)
            } catch (e: Exception) {
                AppLog.w("CarEcarxReceiver: unsubscribe failed: ${e.message}")
            }
        }
        this.manager = null
        this.subscription = null
        this.context = null
    }

    private fun handleMessage(json: String?) {
        val context = context ?: return
        val message = try {
            JSONObject(json ?: return)
        } catch (_: Exception) {
            return
        }
        if (message.optString("id") != MSG_STATUS_REQUEST) return
        val dataType = message.optJSONObject("payload")?.optInt("dataType", -1) ?: -1
        AppLog.i("CarEcarxReceiver: media status request dataType=$dataType")
        keyCodeFor(dataType)?.let { handleClick(context, it) }
    }

    /** Server side of the firmware's `ISystemHMICallback` AIDL interface. */
    private class MediaCallbackBinder(
        private val getDomainId: Int,
        private val getState: Int,
        private val onReceive: Int,
        private val onMessage: (String?) -> Unit,
    ) : Binder() {

        init {
            attachInterface(null, CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> reply?.writeString(CALLBACK)
                getDomainId -> {
                    data.enforceInterface(CALLBACK)
                    reply?.writeNoException()
                    reply?.writeString(DOMAIN)
                }
                getState -> {
                    data.enforceInterface(CALLBACK)
                    data.readString()
                    reply?.writeNoException()
                }
                onReceive -> {
                    data.enforceInterface(CALLBACK)
                    val json = data.readString()
                    reply?.writeNoException()
                    onMessage(json)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }
}
