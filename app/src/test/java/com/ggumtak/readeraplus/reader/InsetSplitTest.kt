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
    fun theDisplaysCornersRelativeToThePageView() {
        // S25 portrait fullscreen, a 132 px corner at each top corner of the 1080 px window; the page view fills it.
        val window = intArrayOf(132, 132, 132, 132, 1080 - 132, 132)
        val out = IntArray(6)
        InsetSplit.pageCorners(window, 0, 0, 1080, out)
        assertEquals(listOf(132, 132, 132, 132, 132, 132), out.toList())
        // Bars shown: the view starts below the 110 px status bar, so the corners' centres are 22 px below its top.
        InsetSplit.pageCorners(window, 0, 110, 1080, out)
        assertEquals(listOf(132, 132, 22, 132, 132, 22), out.toList())
        // Landscape with the camera on the left: the view starts 87 px in (2340 px wide window).
        InsetSplit.pageCorners(intArrayOf(132, 132, 132, 132, 2340 - 132, 132), 87, 0, 2340, out)
        assertEquals(listOf(132, 45, 132, 132, 132, 132), out.toList())
        // No rounded corner (the Comet, a split screen's inner corner) and unknown ones (before API 31) carry no centre.
        InsetSplit.pageCorners(intArrayOf(0, 5, 5, -1, 7, 7), 0, 0, 720, out)
        assertEquals(listOf(0, 0, 0, -1, 0, 0), out.toList())
    }

    @Test
    fun oddValuesStayInRange() {
        assertEquals(0, InsetSplit.cutoutTop(-5, 0))
        assertEquals(0, InsetSplit.pageTopMargin(-5, 0))
        assertEquals(0, InsetSplit.pageTopMargin(40, 90))   // a band larger than the inset leaves no margin
        assertEquals(40, InsetSplit.pageTopMargin(40, -3))
    }
}
