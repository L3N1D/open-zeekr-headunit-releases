package com.lenid.zoh.aap

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.lenid.zoh.utils.AppLog
import java.lang.reflect.Proxy

/** A process-wide listener: this firmware unsubscribes its singleton from HMI on last unregister. */
internal class ClusterPhoneSdk private constructor(context: Context) {
    private val loader = context.classLoader
    private val dimType = loader.loadClass("com.ecarx.xui.adaptapi.diminteraction.DimInteraction")
    private val phoneType = loader.loadClass("com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction")
    private val infoType = loader.loadClass(phoneType.name + "\$ICallInfo")
    private val callbackType = loader.loadClass(phoneType.name + "\$IPhoneCallInteractionCallback")
    private val dim = dimType.getMethod("create", Context::class.java).invoke(null, context)
    private val phone = dimType.getMethod("getPhoneCallInteraction").invoke(dim)
    private val arrayType = java.lang.reflect.Array.newInstance(infoType, 0).javaClass
    private val update = phoneType.getMethod("updateCallInfos", arrayType)
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile var listener: ((String, String) -> Unit)? = null

    init {
        val callback = proxy(callbackType) { method, args ->
            if (method == "onAnswerCall" || method == "onEndCall" || method == "onCallInfoUpdateRequired") {
                val target = listener
                val number = args?.firstOrNull() as? String ?: ""
                main.post { if (target === listener) target?.invoke(method, number) }
            }
            null
        }
        phoneType.getMethod("registerPhoneCallback", callbackType).invoke(phone, callback)
    }

    private fun proxy(type: Class<*>, value: (String, Array<out Any?>?) -> Any?): Any =
        Proxy.newProxyInstance(loader, arrayOf(type)) { obj, method, args ->
            when (method.name) {
                "equals" -> obj === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(obj)
                "toString" -> type.simpleName
                else -> value(method.name, args)
            }
        }

    fun publish(call: ClusterCall?) {
        val count = if (call == null) 0 else 1
        val info = proxy(infoType) { method, _ ->
            when (method) {
                "getCallCount", "getActiveCallId", "getCallId" -> count
                "getIndex" -> 0
                "getCallDuration" -> call?.durationMs ?: 0L
                "getCallStatus" -> call?.state?.zeekrStatus ?: 0
                "getContactName" -> call?.name
                "getContactNumber" -> call?.number
                "isMicOnVehicleMuted" -> audio.isMicrophoneMute
                "isHandFree" -> call != null
                "isRingtoneMuted" -> false
                else -> null
            }
        }
        val entries = java.lang.reflect.Array.newInstance(infoType, 1)
        java.lang.reflect.Array.set(entries, 0, info)
        update.invoke(phone, entries)
    }

    companion object {
        private var instance: ClusterPhoneSdk? = null

        @Synchronized fun acquire(context: Context): ClusterPhoneSdk? = try {
            instance ?: ClusterPhoneSdk(context.applicationContext).also { instance = it }
        } catch (e: Exception) {
            AppLog.w("Cluster phone API unavailable: " + (e.cause ?: e).javaClass.simpleName)
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
