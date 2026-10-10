package com.ggumtak.readeraplus.reader.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A setting typed into the 톱니 popup's number pad (user, 2026-10-10). */
class StepperEntryTest {

    @Test
    fun percentsAreTakenAsTypedNotSnappedToTheStep() {
        val e = StepperEntry()
        assertEquals(158f, e.valueFor(158.0, 100f, 300f)!!, 0f)
        assertEquals(100f, e.valueFor(100.0, 100f, 300f)!!, 0f)
        assertNull(e.valueFor(99.0, 100f, 300f))
        assertNull(e.valueFor(301.0, 100f, 300f))
        assertEquals("100~300", e.range(100f, 300f))
        assertEquals("160", e.shown(160f))
    }

    @Test
    fun fontSizeTakesOneDecimal() {
        val e = StepperEntry(decimals = 1, digits = 2)
        assertEquals(17.5f, e.valueFor(17.5, 8f, 60f)!!, 0f)
        assertEquals(17.3f, e.valueFor(17.34, 8f, 60f)!!, 1e-4f)
        assertNull(e.valueFor(7.9, 8f, 60f))
        assertEquals("17.5", e.shown(17.5f))
        assertEquals("17", e.shown(17f))
        assertEquals("8~60", e.range(8f, 60f))
    }

    @Test
    fun marginsAreSigned() {
        val e = StepperEntry(signed = true, digits = 2)
        assertEquals(-7f, e.valueFor(-7.0, -15f, 70f)!!, 0f)
        assertEquals(7f, e.valueFor(7.0, -15f, 70f)!!, 0f)
        assertNull(e.valueFor(-16.0, -15f, 70f))
        assertEquals("−15~70", e.range(-15f, 70f))
        assertEquals("−4", e.shown(-4f))
        assertEquals("0", e.shown(0f))
    }

    @Test
    fun weightIsTypedInStepsFromItsNaturalWeight() {
        val natural = 400
        val e = StepperEntry(signed = true, digits = 2, toShown = { (it - natural) / 50.0 }, fromShown = { natural + it.toFloat() * 50f })
        assertEquals(500f, e.valueFor(2.0, 100f, 900f)!!, 0f)
        assertEquals(350f, e.valueFor(-1.0, 100f, 900f)!!, 0f)
        // a static font cannot go below its own weight
        assertNull(e.valueFor(-1.0, 400f, 900f))
        assertEquals("0~10", e.range(400f, 900f))
        assertEquals("2", e.shown(500f))
    }
}
