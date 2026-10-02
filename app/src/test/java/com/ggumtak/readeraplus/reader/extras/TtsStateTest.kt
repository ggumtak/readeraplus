package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsStateTest {
    @Test fun onlyAUserPauseCanStopTheIdleService() {
        assertFalse(TtsState(1, "책", "1화", true, 60_000).idleStopAllowed)
        assertFalse(TtsState(1, "책", "1화", false, 60_000, holdForeground = true).idleStopAllowed)
        assertTrue(TtsState(1, "책", "1화", false, 60_000).idleStopAllowed)
    }

    @Test fun changingFocusPauseDoesNotRepostTheSameNotification() {
        val paused = TtsState(1, "책", "1화", false, 60_000)
        val focusPaused = TtsState(1, "책", "1화", false, 60_000, holdForeground = true)
        assertTrue(focusPaused.sameShown(paused))
        assertTrue(paused.sameShown(focusPaused))
    }
}
