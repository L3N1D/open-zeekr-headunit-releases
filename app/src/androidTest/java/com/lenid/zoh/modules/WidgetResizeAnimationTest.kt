package com.lenid.zoh.modules

import android.animation.ValueAnimator
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.lenid.zoh.view.ProjectionResizeMotion
import org.junit.Assert.*
import org.junit.Test

class WidgetResizeAnimationTest {
    @Test fun bothWidgetPositionsKeepTheSurfaceAndFade() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync {
            CodeModules.initialize(context)
            val host = FrameLayout(context)
            val surface = View(context)
            host.addView(surface)
            val motion = ProjectionResizeMotion(host) { true }
            val animatorField = motion.javaClass.getDeclaredField("animator").apply { isAccessible = true }
            val shade = motion.javaClass.getDeclaredField("shade").apply { isAccessible = true }.get(motion) as View
            try {
                host.layout(0, 0, 1275, 876)
                assertNull(animatorField.get(motion))
                for (width in listOf(1796, 1275)) {
                    val direction = if (width > host.width) 1 else -1
                    repeat(4) { host.layout(0, 0, host.width + 30 * direction, 876) }
                    val intermediate = animatorField.get(motion)
                    host.layout(0, 0, width, 876)
                    assertSame(intermediate, animatorField.get(motion))
                    val animation = animatorField.get(motion) as ValueAnimator
                    assertEquals(450L, animation.duration)
                    animation.currentPlayTime = 225
                    assertEquals(0.28f, shade.alpha, 0.01f)
                    assertEquals(width, shade.width)
                    animation.end()
                    assertEquals(0f, shade.alpha, 0.001f)
                    assertSame(surface, host.getChildAt(0))
                }
            } finally {
                motion.dispose()
            }
        }
    }
}
