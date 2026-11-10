package com.lenid.zoh.main

import android.app.Activity
import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LiveProjectionWindowTest {
    private fun window(task: Int = 42, displayId: Int = 3): Activity {
        val display = mock<Display>()
        whenever(display.displayId).thenReturn(displayId)
        return mock<Activity>().also {
            whenever(it.taskId).thenReturn(task)
            whenever(it.display).thenReturn(display)
        }
    }

    @Test fun `covered projection survives repeated launcher entries and display moves`() {
        val registry = LiveProjectionWindow()
        val projection = window()
        val entry = window()
        registry.attach(projection)
        // A paused/stopped window is still alive; neither focus nor bounds determine reuse.
        repeat(3) { assertTrue(registry.isBehind(entry)) }
        val movedDisplay = mock<Display>()
        whenever(movedDisplay.displayId).thenReturn(4)
        whenever(projection.display).thenReturn(movedDisplay)
        assertFalse(registry.isBehind(entry))
        whenever(entry.display).thenReturn(movedDisplay)
        assertTrue(registry.isBehind(entry))
    }

    @Test fun `missing finishing destroyed and detached windows do not suppress launch`() {
        val registry = LiveProjectionWindow()
        val entry = window()
        val projection = window()
        assertFalse(registry.isBehind(entry))
        registry.attach(projection)
        whenever(projection.isFinishing).thenReturn(true)
        assertFalse(registry.isBehind(entry))
        whenever(projection.isFinishing).thenReturn(false)
        whenever(projection.isDestroyed).thenReturn(true)
        assertFalse(registry.isBehind(entry))
        whenever(projection.isDestroyed).thenReturn(false)
        registry.detach(projection)
        assertFalse(registry.isBehind(entry))
    }

    @Test fun `projection in another task or display cannot be uncovered by finishing entry`() {
        val registry = LiveProjectionWindow()
        val projection = window()
        registry.attach(projection)
        assertFalse(registry.isBehind(window(task = 43)))
        assertFalse(registry.isBehind(window(displayId = 0)))
    }

    @Test fun `old instance teardown does not discard replacement`() {
        val registry = LiveProjectionWindow()
        val old = window()
        val replacement = window()
        registry.attach(old)
        registry.attach(replacement)
        registry.detach(old)
        assertTrue(registry.isBehind(window()))
        registry.detach(replacement)
        assertFalse(registry.isBehind(window()))
    }
}
