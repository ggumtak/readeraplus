package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Test

class FontWeightFloorTest {

    @Test
    fun staticFontsCannotGoBelowRegular() {
        assertEquals(400, FontMath.minWeight(variable = false, system = false))
        assertEquals(100, FontMath.minWeight(variable = true, system = false))
        assertEquals(100, FontMath.minWeight(variable = false, system = true))
    }

    @Test
    fun lightWeightsOnStaticFontsKeepBoldRunsBold() {
        // Regression: base 100 on a static font drew bold runs at 400 (no bold file, no stroke).
        val min = FontMath.minWeight(variable = false, system = false)
        val base = FontMath.effectiveBase(100, min)
        assertEquals(400, base)
        assertEquals(700, FontMath.runWeight(base, bold = true))
        assertEquals(FontMath.syntheticStroke(700, false, 30f), FontMath.syntheticStroke(FontMath.runWeight(FontMath.effectiveBase(300, min), true), false, 30f), 0f)
        // Heavier weights are unchanged; variable fonts keep light weights.
        assertEquals(650, FontMath.effectiveBase(650, min))
        assertEquals(900, FontMath.effectiveBase(1200, min))
        assertEquals(200, FontMath.effectiveBase(200, FontMath.minWeight(variable = true, system = false)))
        assertEquals(100, FontMath.effectiveBase(0, 100))
    }
}
