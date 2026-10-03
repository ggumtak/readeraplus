package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.settings.*
import org.junit.Assert.*
import org.junit.Test

class QuoteLookTest {
    @Test fun autoIsColourUntilTheProbeIdentifiesInk() {
        QuoteLook.update(HL_LOOK_AUTO, null)
        assertFalse(QuoteLook.ink())
        val before = QuoteLook.generation
        QuoteLook.update(HL_LOOK_AUTO, true)
        assertTrue(QuoteLook.ink())
        assertEquals(before + 1, QuoteLook.generation)
        QuoteLook.update(HL_LOOK_AUTO, true)
        assertEquals(before + 1, QuoteLook.generation)
        QuoteLook.update(HL_LOOK_AUTO, false)
        assertFalse(QuoteLook.ink())
    }
    @Test fun overridesIgnoreTheProbeAndOnlyActualLookChangesInvalidatePaints() {
        QuoteLook.update(HL_LOOK_INK, false)
        assertTrue(QuoteLook.ink())
        val before = QuoteLook.generation
        QuoteLook.update(HL_LOOK_INK, null)
        QuoteLook.update(HL_LOOK_AUTO, true)
        assertEquals(before, QuoteLook.generation)
        QuoteLook.update(HL_LOOK_COLOR, true)
        assertFalse(QuoteLook.ink())
        assertEquals(before + 1, QuoteLook.generation)
        QuoteLook.update(HL_LOOK_COLOR, null)
        assertEquals(before + 1, QuoteLook.generation)
        QuoteLook.update(HL_LOOK_AUTO, null)
    }
}
