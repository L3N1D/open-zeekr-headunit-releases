package com.lenid.zoh.main

import android.app.Activity
import java.lang.ref.WeakReference

/** Tracks window existence, independently of the focus lost when the launcher covers it. */
internal class LiveProjectionWindow {
    private var owner = WeakReference<Activity>(null)

    private fun liveOwner() = owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun exists() = liveOwner() != null

    fun attach(activity: Activity) {
        owner = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (owner.get() === activity) owner.clear()
    }

    // Called on Android R+ only. Read the current display, not a cached launch destination:
    // the launcher may resize or move the card while this window is covered.
    @android.annotation.TargetApi(30)
    fun isBehind(entry: Activity): Boolean {
        return owner.get()?.taskId == entry.taskId && isOnDisplay(entry)
    }

    @android.annotation.TargetApi(30)
    fun isOnDisplay(entry: Activity): Boolean {
        val activity = liveOwner() ?: return false
        val displayId = activity.display?.displayId ?: return false
        return displayId == entry.display?.displayId
    }
}
