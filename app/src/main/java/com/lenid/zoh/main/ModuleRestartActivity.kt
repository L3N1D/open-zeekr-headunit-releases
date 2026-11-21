package com.lenid.zoh.main

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lenid.zoh.App
import com.lenid.zoh.connection.CommManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.ResultReceiver
import androidx.core.app.BundleCompat

/** Runs briefly in its own process so the new main task starts after the old process exits. */
class ModuleRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent.extras
        val token = extras?.let { BundleCompat.getBinder(it, "lifetime") }
        val stop = extras?.getParcelable<ResultReceiver>("stop")
        if (token == null || stop == null) {
            finish()
            return
        }
        val relaunch = Runnable {
            if (!isFinishing) {
                startActivity(Intent.makeRestartActivityTask(android.content.ComponentName(this, MainActivity::class.java)))
                finish()
            }
        }
        val main = Handler(Looper.getMainLooper())
        try {
            token.linkToDeath(IBinder.DeathRecipient { main.post(relaunch) }, 0)
            stop.send(0, null)
        } catch (_: RemoteException) {
            main.post(relaunch)
        }
    }

    companion object {
        private var requested = false

        fun observe(activity: FragmentActivity) {
            activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    com.lenid.zoh.utils.ModuleUpdates.restartPending.collect { pending ->
                        if (pending) {
                            while (com.lenid.zoh.connection.ConnectionArbiter.projectionReserved() ||
                                App.provide(activity).commManager.connectionState.value !is CommManager.ConnectionState.Disconnected ||
                                com.lenid.zoh.dongle.DongleService.state.value.active ||
                                com.lenid.zoh.carplay.DirectCarPlayService.sessionRunning()) delay(500)
                            restart(activity)
                        }
                    }
                }
            }
        }

        private fun restart(activity: Activity) {
            if (requested) return
            val args = Bundle()
            BundleCompat.putBinder(args, "lifetime", Binder())
            args.putParcelable("stop", object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    Process.killProcess(Process.myPid())
                }
            })
            activity.startActivity(Intent(activity, ModuleRestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtras(args))
            requested = true
        }
    }
}
