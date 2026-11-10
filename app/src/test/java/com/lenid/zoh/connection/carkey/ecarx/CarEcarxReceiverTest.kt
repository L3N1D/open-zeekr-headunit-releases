package com.lenid.zoh.connection.carkey.ecarx

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarEcarxReceiverTest {

    @Test
    fun `wheel buttons captured on a Zeekr map to media keys`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, CarEcarxReceiver.keyCodeFor(5))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, CarEcarxReceiver.keyCodeFor(6))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, CarEcarxReceiver.keyCodeFor(7))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, CarEcarxReceiver.keyCodeFor(8))
    }

    @Test
    fun `media centre alternative next and previous codes are honoured`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, CarEcarxReceiver.keyCodeFor(12))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, CarEcarxReceiver.keyCodeFor(13))
    }

    @Test
    fun `status queries are not key presses`() {
        for (dataType in listOf(-1, 0, 1, 3, 4, 9, 10, 17, 18, 22)) {
            assertNull("dataType=$dataType", CarEcarxReceiver.keyCodeFor(dataType))
        }
    }
}
