package com.lenid.zoh.utils

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.Interpolator
import androidx.annotation.RequiresApi
import java.util.function.Consumer

/** Compositor blur on supported Android 12+ devices, live dimming everywhere else. */
internal class ConnectionWindowEffect(private val window: Window, private val scrim: View) {
    private var animator: ValueAnimator? = null
    private var native: NativeBlur? = null
    private var progress = 0f
    private var visible = true
    private var blurAvailable = false

    fun attach() {
        if (Build.VERSION.SDK_INT >= 31 && native == null) {
            native = NativeBlur(window) { enabled -> blurAvailable = enabled; apply() }
            native?.attach()
        }
        apply()
    }

    fun animateTo(target: Float, duration: Long, easing: Interpolator) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration
            interpolator = easing
            addUpdateListener { progress = it.animatedValue as Float; apply() }
            start()
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        apply()
    }

    private fun apply() {
        val amount = if (visible) progress else 0f
        scrim.alpha = amount * if (blurAvailable) 0.18f else 0.42f
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = (24 * window.context.resources.displayMetrics.density * amount).toInt()
            native?.setRadius(if (blurAvailable) radius else 0)
        }
    }

    fun detach() {
        animator?.cancel(); animator = null
        if (Build.VERSION.SDK_INT >= 31) native?.detach()
        native = null
    }

    @RequiresApi(31)
    private class NativeBlur(private val window: Window, private val changed: (Boolean) -> Unit) {
        private val manager = window.windowManager
        private val listener = Consumer<Boolean> { changed(it) }
        private var registered = false
        private var radius = -1

        fun attach() {
            changed(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, listener)
            registered = true
        }
        fun setRadius(value: Int) {
            if (value == radius) return
            radius = value
            window.attributes = window.attributes.apply {
                blurBehindRadius = value
                flags = if (value > 0) flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    else flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
        }
        fun detach() {
            if (registered) manager.removeCrossWindowBlurEnabledListener(listener)
            registered = false
            setRadius(0)
        }
    }
}
