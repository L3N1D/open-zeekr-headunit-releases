package com.lenid.zoh.utils

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDialogKeyPolicyTest {

    @Test
    fun lastFieldAsksForDoneAndEarlierFieldsForNext() {
        assertEquals(EditorInfo.IME_ACTION_DONE, TextDialogKeyPolicy.imeAction(isLastField = true))
        assertEquals(EditorInfo.IME_ACTION_NEXT, TextDialogKeyPolicy.imeAction(isLastField = false))
    }

    @Test
    fun doneGoAndSendPressOk() {
        assertTrue(TextDialogKeyPolicy.isSubmit(EditorInfo.IME_ACTION_DONE, null, null))
        assertTrue(TextDialogKeyPolicy.isSubmit(EditorInfo.IME_ACTION_GO, null, null))
        assertTrue(TextDialogKeyPolicy.isSubmit(EditorInfo.IME_ACTION_SEND, null, null))
    }

    @Test
    fun nextDoesNotPressOk() {
        assertFalse(TextDialogKeyPolicy.isSubmit(EditorInfo.IME_ACTION_NEXT, null, null))
    }

    @Test
    fun plainEnterPressesOkOnceOnKeyDown() {
        assertTrue(
            TextDialogKeyPolicy.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN),
        )
        assertFalse(
            TextDialogKeyPolicy.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun oemKeyboardWithNoActionAndNoEventPressesOk() {
        assertTrue(TextDialogKeyPolicy.isSubmit(EditorInfo.IME_NULL, null, null))
    }

    @Test
    fun otherKeysDoNotPressOk() {
        assertFalse(TextDialogKeyPolicy.isSubmit(EditorInfo.IME_NULL, KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN))
    }
}
