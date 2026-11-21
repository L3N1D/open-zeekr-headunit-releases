package com.lenid.zoh.view

import com.lenid.zoh.modules.ModuleFunction
import com.lenid.zoh.modules.CodeModule
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.abs

/** UI-only shading; never changes video transforms, surfaces or transport. */
internal class ProjectionResizeMotion(private val host: ViewGroup, private val enabled: () -> Boolean) {
    private val shade = View(host.context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var animator: ValueAnimator? = null
    private var anchorWidth = 0
    private var anchorHeight = 0
    private val settle = Runnable {
        anchorWidth = host.width
        anchorHeight = host.height
    }
    private val listener = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
        shade.layout(0, 0, r - l, b - t)
        if (anchorWidth == 0 || anchorHeight == 0) {
            anchorWidth = or - ol
            anchorHeight = ob - ot
        }
        if (!enabled() || anchorWidth <= 0 || anchorHeight <= 0) {
            anchorWidth = r - l
            anchorHeight = b - t
            animator?.cancel()
            shade.alpha = 0f
        } else if (animator?.isRunning != true && ProjectionResizeMotionPolicy.shouldAnimate(
                anchorWidth, anchorHeight, r - l, b - t)) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = (ModuleFunction.RESIZE_DURATION.evaluate(r - l - anchorWidth) ?: 450).toLong()
                interpolator = LinearInterpolator()
                addUpdateListener {
                    // A cosine pulse has zero slope at both ends and at peak shading.
                    shade.alpha = (0.14 * (1 - cos(2 * PI * it.animatedFraction))).toFloat()
                }
                anchorWidth = r - l
                anchorHeight = b - t
                start()
            }
        }
        host.removeCallbacks(settle)
        host.postDelayed(settle, 100)
    }

    init {
        host.overlay.add(shade)
        host.addOnLayoutChangeListener(listener)
    }

    fun dispose() {
        host.removeCallbacks(settle)
        host.removeOnLayoutChangeListener(listener)
        animator?.cancel()
        shade.alpha = 0f
        host.overlay.remove(shade)
    }
}

internal object ProjectionResizeMotionPolicy {
    fun shouldAnimate(oldWidth: Int, oldHeight: Int, width: Int, height: Int): Boolean =
        CodeModule.UI.evaluate(oldWidth, oldHeight, width, height)?.let { it == 1 } ?: (
            oldWidth > 0 && oldHeight > 0 && width > 0 && height > 0 &&
            abs(width - oldWidth) >= 100 && abs(height - oldHeight) <= 32)
}
