package com.lenid.zoh.view

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/** UI-only shading; never changes video transforms, surfaces or transport. */
internal class ProjectionResizeMotion(private val host: ViewGroup, private val enabled: () -> Boolean) {
    private val shade = View(host.context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var animator: ValueAnimator? = null
    private val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        shade.layout(0, 0, r - l, b - t)
        if (!enabled()) {
            animator?.cancel()
            shade.alpha = 0f
        } else if (animator?.isRunning != true && ProjectionResizeMotionPolicy.shouldAnimate(
                or - ol, ob - ot, r - l, b - t)) {
            animator = ValueAnimator.ofFloat(0f, 0.28f, 0f).apply {
                duration = 450
                addUpdateListener { shade.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    init {
        host.overlay.add(shade)
        host.addOnLayoutChangeListener(listener)
    }

    fun dispose() {
        host.removeOnLayoutChangeListener(listener)
        animator?.cancel()
        shade.alpha = 0f
        host.overlay.remove(shade)
    }
}

internal object ProjectionResizeMotionPolicy {
    fun shouldAnimate(oldWidth: Int, oldHeight: Int, width: Int, height: Int): Boolean =
        oldWidth > 0 && oldHeight > 0 && width > 0 && height > 0 &&
            abs(width - oldWidth) >= 100 && abs(height - oldHeight) <= 32
}
