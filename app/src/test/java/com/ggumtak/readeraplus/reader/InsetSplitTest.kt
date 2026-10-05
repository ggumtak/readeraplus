package com.ggumtak.readeraplus.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** ReaderWindow.insetsOf / ReaderActivity.applyPageInsets: which part of the top inset the page view reaches into. */
class InsetSplitTest {
    private fun split(top: Int, barsTop: Int): Pair<Int, Int> {
        val cut = InsetSplit.cutoutTop(top, barsTop)
        return InsetSplit.pageTopMargin(top, cut) to cut
    }

    @Test
    fun fullscreenS25ReachesIntoTheCameraBand() {
        // Bars hidden, the punch hole's safe inset alone: no margin, the whole inset is the band the header uses.
        assertEquals(0 to 87, split(top = 87, barsTop = 0))
    }

    @Test
    fun visibleBarsKeepThePageBelowThem() {
        // The status bar (which holds the camera) shows: the page goes below it as before, no band.
        assertEquals(110 to 0, split(top = 110, barsTop = 110))
    }

    @Test
    fun noTopCutoutNoBand() {
        // S25 landscape (the cutout is at the side) and reverse portrait (at the bottom), fullscreen.
        assertEquals(0 to 0, split(top = 0, barsTop = 0))
        // The Comet: no cutout, bars hidden or a bar the firmware keeps (the page stays below it).
        assertEquals(0 to 0, split(top = 0, barsTop = 0))
        assertEquals(48 to 0, split(top = 48, barsTop = 48))
    }

    @Test
    fun beforeApi30FullscreenCountsTheCutoutAsBand() {
        // insetsOf passes barsTop 0 there (no bar shows in fullscreen); outside fullscreen the cutout part is 0.
        assertEquals(0 to 87, split(top = 87, barsTop = 0))
        assertEquals(0, InsetSplit.pageTopMargin(87, 87))
        assertEquals(110, InsetSplit.pageTopMargin(110, 0))
    }

    @Test
    fun oddValuesStayInRange() {
        assertEquals(0, InsetSplit.cutoutTop(-5, 0))
        assertEquals(0, InsetSplit.pageTopMargin(-5, 0))
        assertEquals(0, InsetSplit.pageTopMargin(40, 90))   // a band larger than the inset leaves no margin
        assertEquals(40, InsetSplit.pageTopMargin(40, -3))
    }
}
