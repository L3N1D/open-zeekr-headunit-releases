package com.lenid.zoh.utils

import android.app.Activity
import android.graphics.RectF
import android.view.View

/** Only the tapped tile's normalized bounds. No image or View is retained. */
internal object ConnectionOrigin {
    private var bounds: RectF? = null

    fun remember(activity: Activity, anchor: View?) {
        bounds = null
        val root = activity.findViewById<View>(android.R.id.content) ?: return
        if (root.width <= 0 || root.height <= 0 || anchor == null || anchor.width <= 0 || anchor.height <= 0) return
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val x = (location[0] - rootLocation[0]).toFloat()
        val y = (location[1] - rootLocation[1]).toFloat()
        bounds = RectF(x / root.width, y / root.height,
            (x + anchor.width) / root.width, (y + anchor.height) / root.height)
    }

    fun takeBounds(): RectF? = bounds.also { bounds = null }
}
