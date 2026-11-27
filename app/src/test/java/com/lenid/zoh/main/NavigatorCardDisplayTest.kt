package com.lenid.zoh.main

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class NavigatorCardDisplayTest {
    private fun display(id: Int): Display = mock<Display>().also {
        whenever(it.displayId).thenReturn(id)
        whenever(it.isValid).thenReturn(true)
    }

    @Test fun cardSurvivesReturnToMainDisplayAndIsReplacedWhenLauncherRecreatesIt() {
        val target = NavigatorCardDisplay()
        assertNull(target.currentId())
        val first = display(3)
        target.remember(first)
        target.remember(display(Display.DEFAULT_DISPLAY))
        assertEquals(3, target.currentId())
        whenever(first.isValid).thenReturn(false)
        assertNull(target.currentId())
        target.remember(display(7))
        assertEquals(7, target.currentId())
    }
}
