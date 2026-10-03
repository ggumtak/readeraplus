package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeekRuleTest {
    @Test fun normalReadingSaves() {
        val p = PeekRule()
        assertFalse(p.active)
        assertTrue(p.savesPosition)
        assertFalse(p.on(PeekRule.Event.MANUAL_TURN))
    }

    @Test fun anOpenAtANoteStopsSavingUntilTheFirstTurn() {
        val p = PeekRule()
        p.start()
        assertFalse(p.savesPosition)
        // Relayouts, the anchor check's move and TTS / auto-turn's own turns keep peeking.
        for (e in listOf(PeekRule.Event.RELAYOUT, PeekRule.Event.ANCHOR_MOVE, PeekRule.Event.FOLLOW)) {
            assertFalse(p.on(e))
            assertTrue(p.active)
        }
        assertTrue(p.on(PeekRule.Event.MANUAL_TURN))
        assertTrue(p.savesPosition)
        assertFalse(p.on(PeekRule.Event.MANUAL_TURN))
    }

    @Test fun everyUserActionOfN62EndsIt() {
        val ending = listOf(
            PeekRule.Event.MANUAL_TURN, PeekRule.Event.TTS_START, PeekRule.Event.AUTO_TURN_START,
            PeekRule.Event.USER_JUMP, PeekRule.Event.RETURN_POINT, PeekRule.Event.READ_HERE,
            PeekRule.Event.SCROLL_SETTLE,
        )
        for (e in PeekRule.Event.values()) {
            val p = PeekRule()
            p.start()
            assertEquals(e.name, e in ending, p.on(e))
            assertEquals(e.name, e in ending, PeekRule.ends(e))
        }
    }

    @Test fun restoredAndResetStates() {
        val p = PeekRule()
        p.restore(true)
        assertFalse(p.savesPosition)
        p.reset()
        assertTrue(p.savesPosition)
        p.restore(false)
        assertTrue(p.savesPosition)
    }
}
