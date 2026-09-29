package com.ggumtak.readeraplus.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyCaptureTest {
    @Test
    fun assignsOnDownAndClosesOnSameKeyUp() {
        val c = KeyCapture()
        assertEquals(KeyCapture.Step.ASSIGN, c.onKey(down = true, keyCode = KeyNames.VOLUME_DOWN, repeatCount = 0))
        assertEquals(KeyNames.VOLUME_DOWN, c.captured)
        assertEquals(KeyCapture.Step.CLOSE, c.onKey(down = false, keyCode = KeyNames.VOLUME_DOWN, repeatCount = 0))
    }

    @Test
    fun ignoresRepeatsOtherKeysAndStrayUps() {
        val c = KeyCapture()
        // UP of a key pressed before the dialog opened.
        assertEquals(KeyCapture.Step.IGNORE, c.onKey(down = false, keyCode = KeyNames.PAGE_DOWN, repeatCount = 0))
        // Auto-repeat without a first press is not a capture.
        assertEquals(KeyCapture.Step.IGNORE, c.onKey(down = true, keyCode = KeyNames.PAGE_DOWN, repeatCount = 3))
        assertEquals(-1, c.captured)
        assertEquals(KeyCapture.Step.ASSIGN, c.onKey(down = true, keyCode = KeyNames.PAGE_DOWN, repeatCount = 0))
        assertEquals(KeyCapture.Step.IGNORE, c.onKey(down = true, keyCode = KeyNames.PAGE_DOWN, repeatCount = 1))
        assertEquals(KeyCapture.Step.IGNORE, c.onKey(down = true, keyCode = KeyNames.VOLUME_UP, repeatCount = 0))
        assertEquals(KeyCapture.Step.IGNORE, c.onKey(down = false, keyCode = KeyNames.VOLUME_UP, repeatCount = 0))
        assertEquals(KeyNames.PAGE_DOWN, c.captured)
        assertEquals(KeyCapture.Step.CLOSE, c.onKey(down = false, keyCode = KeyNames.PAGE_DOWN, repeatCount = 0))
    }

    @Test
    fun rejectsSystemKeysAndKeepsWaiting() {
        val c = KeyCapture()
        assertEquals(KeyCapture.Step.REJECT, c.onKey(down = true, keyCode = KeyNames.POWER, repeatCount = 0))
        assertEquals(KeyCapture.Step.REJECT, c.onKey(down = true, keyCode = KeyNames.HOME, repeatCount = 0))
        assertEquals(-1, c.captured)
        assertEquals(KeyCapture.Step.IGNORE, c.onKey(down = false, keyCode = KeyNames.POWER, repeatCount = 0))
        assertEquals(KeyCapture.Step.ASSIGN, c.onKey(down = true, keyCode = 131, repeatCount = 0))
    }

    @Test
    fun unknownKeyCodeZeroIsCapturable() {
        val c = KeyCapture()
        assertEquals(KeyCapture.Step.ASSIGN, c.onKey(down = true, keyCode = KeyNames.UNKNOWN, repeatCount = 0))
        assertEquals(0, c.captured)
        assertEquals(KeyCapture.Step.CLOSE, c.onKey(down = false, keyCode = KeyNames.UNKNOWN, repeatCount = 0))
    }
}
