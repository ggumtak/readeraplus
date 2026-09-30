package com.ggumtak.readeraplus.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Test

class InkMessageLayoutTest {
    @Test
    fun edgeToEdgeWindowAddsTheVisibleBar() {
        // Reader: content spans the whole 1440px window, a 48px navigation bar is showing.
        assertEquals(96 + 48, inkMessageBottomMargin(contentBottomInWindow = 1440, windowHeight = 1440, barInsetBottom = 48, base = 96))
    }

    @Test
    fun windowFittingAboveTheBarKeepsBase() {
        // Library: the decor already stops the content above the 48px bar.
        assertEquals(96, inkMessageBottomMargin(contentBottomInWindow = 1392, windowHeight = 1440, barInsetBottom = 48, base = 96))
    }

    @Test
    fun hiddenBarsAndBadInsets() {
        assertEquals(96, inkMessageBottomMargin(1440, 1440, 0, 96))
        assertEquals(96, inkMessageBottomMargin(1440, 1440, -5, 96))
        // Keyboard taller than the part of the content it covers: only the overlap counts.
        assertEquals(96 + 500, inkMessageBottomMargin(1392, 1440, 548, 96))
    }
}
