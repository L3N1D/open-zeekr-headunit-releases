package com.lenid.zoh.mirror

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import com.lenid.zoh.utils.AppLog

/**
 * Lets the "OH Route Map" app (and only it) attach its HUD Surface to the cluster video. Raw
 * transact codes keep it free of AIDL (1-4 were the old AA-picture mirror, now gone):
 *  5 = attach a Surface for the cluster video stream ([ClusterVideo])
 *  6 = detach the cluster Surface
 *  7 = cluster status: replies 1 while the phone is streaming the cluster video, else 0
 *  8 = cluster shape: replies content width, content height, frame width, frame height as
 *      announced at connect (the phone draws inside, centred), or nothing before that
 */
class HudMirrorService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 5..8) return super.onTransact(code, data, reply, flags)
            if (!callerAllowed()) {
                AppLog.w("HudMirrorService: refused uid ${getCallingUid()}")
                return false
            }
            when (code) {
                5 -> {
                    ClusterVideo.attachSurface(Surface.CREATOR.createFromParcel(data))
                    AppLog.i("HudMirrorService: cluster surface attached")
                }
                6 -> { ClusterVideo.detachSurface(); AppLog.i("HudMirrorService: cluster surface detached") }
                7 -> reply?.writeInt(if (ClusterVideo.streaming) 1 else 0)
                8 -> ClusterVideo.announced?.let { s ->
                    reply?.writeInt(s.contentWidth); reply?.writeInt(s.contentHeight)
                    reply?.writeInt(ClusterVideo.WIDTH); reply?.writeInt(ClusterVideo.HEIGHT)
                }
            }
            return true
        }
    }

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        return packageManager.getPackagesForUid(uid)?.contains(ROUTE_MAP_PACKAGE) == true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        ClusterVideo.detachSurface()
        return false
    }

    companion object {
        private const val ROUTE_MAP_PACKAGE = "com.lenid.ohroutemap"
    }
}
