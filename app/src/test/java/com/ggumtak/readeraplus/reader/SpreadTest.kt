package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.data.SettingsJson
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The landscape spread (two pages side by side): page math, column geometry, keys, labels and the setting. */
class SpreadTest {
    private val two = ReaderSettings(landscapePages = 2)
    private val density = 2f

    // ---- which page a spread starts at

    @Test
    fun spreadsStartAtEvenPages() {
        assertEquals(0, SpreadMath.start(0))
        assertEquals(0, SpreadMath.start(1))
        assertEquals(2, SpreadMath.start(2))
        assertEquals(2, SpreadMath.start(3))
        assertEquals(3256, SpreadMath.start(3257))
        // Never below the first page.
        assertEquals(0, SpreadMath.start(-1))
        assertEquals(5, SpreadMath.spreads(9))
        assertEquals(5, SpreadMath.spreads(10))
        assertEquals(1, SpreadMath.spreads(1))
        assertEquals(1, SpreadMath.spreads(0))
    }

    @Test
    fun aJumpToPageKShowsTheSpreadThatHoldsIt() {
        assertEquals(4, SpreadMath.shownAt(5, 10))
        assertEquals(4, SpreadMath.shownAt(4, 10))
        // The last page (-2) of an odd and an even section.
        assertEquals(8, SpreadMath.shownAt(TurnMath.LAST_PAGE, 9))
        assertEquals(8, SpreadMath.shownAt(TurnMath.LAST_PAGE, 10))
        // Out of range is clamped to the last spread.
        assertEquals(8, SpreadMath.shownAt(99, 10))
        assertEquals(0, SpreadMath.shownAt(0, 0))
    }

    @Test
    fun theRightSide() {
        // A page follows inside the section.
        assertEquals(SpreadMath.Right.SAME_SECTION, SpreadMath.right(0, 3, hasNext = true, nextLoaded = false))
        assertEquals(SpreadMath.Right.SAME_SECTION, SpreadMath.right(2, 4, hasNext = false, nextLoaded = false))
        // The left page ends the section: the next section's first page when it is laid out, else blank (never waits).
        assertEquals(SpreadMath.Right.BLANK, SpreadMath.right(2, 3, hasNext = true, nextLoaded = true))
        assertEquals(SpreadMath.Right.BLANK, SpreadMath.right(2, 3, hasNext = true, nextLoaded = false))
        // The book's last page.
        assertEquals(SpreadMath.Right.BLANK, SpreadMath.right(2, 3, hasNext = false, nextLoaded = false))
        assertEquals(SpreadMath.Right.BLANK, SpreadMath.right(0, 1, hasNext = false, nextLoaded = true))
    }

    // ---- turns across sections

    @Test
    fun nextAndPrevMoveTwoPagesInsideASection() {
        val a = SpreadMath.walkInSection(1, 0, 1, 3, 9)
        assertEquals(1, a.section); assertEquals(2, a.pageIndex); assertFalse(a.hitEdge)
        val b = SpreadMath.walkInSection(1, 6, 1, 3, 9)
        assertEquals(1, b.section); assertEquals(8, b.pageIndex)
        val c = SpreadMath.walkInSection(1, 8, -1, 3, 9)
        assertEquals(1, c.section); assertEquals(6, c.pageIndex)
        // An odd start (a position inside the right page) counts from its spread.
        val d = SpreadMath.walkInSection(1, 3, 1, 3, 9)
        assertEquals(4, d.pageIndex)
    }

    @Test
    fun aSectionsLastSpreadTurnsToTheNextSectionsFirstPageOnTheLeft() {
        // 9 pages: 0-1 2-3 4-5 6-7 8: from 8 on, next is section 2 page 0 (its pages are not known yet).
        val n = SpreadMath.walkInSection(1, 8, 1, 3, 9)
        assertEquals(2, n.section); assertEquals(0, n.pageIndex); assertEquals(0, n.remaining); assertFalse(n.hitEdge)
        // 10 pages: 8-9 is the last spread; the same.
        val m = SpreadMath.walkInSection(1, 8, 1, 3, 10)
        assertEquals(2, m.section); assertEquals(0, m.pageIndex)
    }

    @Test
    fun backFromASectionsFirstSpreadGoesToThePreviousSectionsLastSpread() {
        val unknown = SpreadMath.walkInSection(1, 0, -1, 3, 9)
        assertEquals(0, unknown.section); assertEquals(TurnMath.LAST_PAGE, unknown.pageIndex)
        // When the previous section's count is known: 7 pages are 0-1 2-3 4-5 6; the last spread starts at 6.
        val known = SpreadMath.walk(1, 0, -1, 3) { if (it == 0) 7 else 9 }
        assertEquals(0, known.section); assertEquals(6, known.pageIndex)
        val even = SpreadMath.walk(1, 0, -1, 3) { if (it == 0) 8 else 9 }
        assertEquals(6, even.pageIndex)
    }

    @Test
    fun theBooksEdgesStopTheWalk() {
        val end = SpreadMath.walkInSection(2, 8, 1, 3, 9)
        assertTrue(end.hitEdge)
        assertEquals(2, end.section); assertEquals(8, end.pageIndex)
        val start = SpreadMath.walkInSection(0, 0, -1, 3, 9)
        assertTrue(start.hitEdge)
        assertEquals(0, start.pageIndex)
    }

    @Test
    fun aBurstOfTurnsWalksSpreads() {
        // +3 from page 2 of a 9-page section: 4, 6, 8.
        assertEquals(8, SpreadMath.walkInSection(0, 2, 3, 2, 9).pageIndex)
        // +4 runs past the section: the fourth takes it to the next section's first page.
        val w = SpreadMath.walk(0, 2, 4, 3) { if (it == 0) 9 else 4 }
        assertEquals(1, w.section); assertEquals(0, w.pageIndex); assertEquals(0, w.remaining)
        // +7 from 2: 4 6 8 | section 1 (4 pages): 0 2 | section 2 (4 pages): 0 2 - the second spread of section 2.
        val x = SpreadMath.walk(0, 2, 7, 3) { if (it == 0) 9 else 4 }
        assertEquals(2, x.section); assertEquals(2, x.pageIndex)
        // A long burst that reaches an unknown section stops on its first page with the turns left.
        val y = SpreadMath.walk(0, 8, 3, 3) { if (it == 0) 9 else -1 }
        assertEquals(1, y.section); assertEquals(0, y.pageIndex); assertEquals(2, y.remaining)
    }

    // ---- the page label of the status line

    private fun label(page: Int, total: Int, end: Int = 0): String {
        val b = CharArray(StatusSlotCapacity)
        val n = StatusText.page(b, 0, page, total, end)
        return String(b, 0, n)
    }

    @Test
    fun theStatusNamesBothPagesOfASpread() {
        assertEquals("12-13 / 3259", label(12, 3259, 13))
        assertEquals("12 / 3259", label(12, 3259))
        // The right page can be the next section's first, a number of its own.
        assertEquals("99-100 / 3259", label(99, 3259, 100))
        // A right page that is not past the left one (blank) reads as a single page.
        assertEquals("3259 / 3259", label(3259, 3259, 0))
        assertEquals("5 / 5", label(5, 5, 5))
        // The total never reads below the pages shown while counting.
        assertEquals("4-5 / 5", label(4, 3, 5))
    }

    @Test
    fun theSinglePageLabelIsUnchanged() {
        assertEquals(ReaderFormat.pageLabel(12, 3259), label(12, 3259))
    }

    // ---- geometry of the columns

    @Test
    fun oneColumnIsTheGeometryItWas() {
        for ((w, h) in listOf(720 to 1440, 1440 to 720, 1080 to 2340)) {
            val a = LayoutKeys.geometry(ReaderSettings(), w, h, density)
            val b = LayoutKeys.geometry(ReaderSettings(), w, h, density, 0, 1)
            assertEquals(a, b)
            assertEquals(1, a.columns)
            assertEquals(0, a.gutter)
            assertEquals(w, a.pageWidth)
            assertEquals(a.contentWidth, a.spanWidth)
        }
    }

    @Test
    fun twoColumnsShareTheTextBoxWithAGutterOfTwiceTheMargin() {
        val one = LayoutKeys.geometry(ReaderSettings(), 1440, 720, density)
        val g = LayoutKeys.geometry(ReaderSettings(), 1440, 720, density, 0, 2)
        assertEquals(2, g.columns)
        // 20 dp side margins at 2 px per dp = 40 px; twice that is 80 px, over the 24 dp (48 px) minimum.
        assertEquals(80, g.gutter)
        assertEquals((one.contentWidth - 80) / 2, g.contentWidth)
        assertEquals(one.contentLeft, g.contentLeft)
        // Height, top and the view are those of the single page.
        assertEquals(one.contentTop, g.contentTop)
        assertEquals(one.contentHeight, g.contentHeight)
        assertEquals(1440, g.viewWidth)
        // The right column starts a column and a gutter after the left one and ends inside the right margin.
        assertEquals(one.contentLeft, g.columnLeft(0))
        assertEquals(one.contentLeft + g.contentWidth + 80, g.columnLeft(1))
        assertTrue(g.columnLeft(1) + g.contentWidth <= 1440 - 40)
        assertTrue(g.columnLeft(1) + g.contentWidth >= 1440 - 40 - 1)
        // A single page of the same view with these margins.
        assertEquals(40 + g.contentWidth + 40, g.pageWidth)
    }

    @Test
    fun theGutterIsAtLeast24DpWhateverTheMargin() {
        val tiny = ReaderSettings(pageMargins = false)
        val g = LayoutKeys.geometry(tiny, 1440, 720, density, 0, 2)
        // 4 dp margins: the gutter is the 24 dp minimum (48 px), not twice 8 px.
        assertEquals(48, g.gutter)
        assertEquals(Math.round(LayoutKeys.MIN_GUTTER_DP * density), g.gutter)
        val wide = ReaderSettings(marginLeftDp = 40, marginRightDp = 40)
        assertEquals(160, LayoutKeys.geometry(wide, 1440, 720, density, 0, 2).gutter)
    }

    @Test
    fun aColumnThatWouldBeTooNarrowLeavesASinglePage() {
        val g = LayoutKeys.geometry(ReaderSettings(), 200, 100, density, 0, 2)
        assertEquals(1, g.columns)
        assertEquals(LayoutKeys.geometry(ReaderSettings(), 200, 100, density), g)
    }

    @Test
    fun theColumnsOfAView() {
        // Only a paged view wider than tall with two pages chosen.
        assertEquals(2, LayoutKeys.columnsFor(2, 1440, 720, paged = true))
        assertEquals(1, LayoutKeys.columnsFor(1, 1440, 720, paged = true))
        assertEquals(1, LayoutKeys.columnsFor(2, 720, 1440, paged = true))
        assertEquals(1, LayoutKeys.columnsFor(2, 1000, 1000, paged = true))
        assertEquals(1, LayoutKeys.columnsFor(2, 1440, 720, paged = false))
    }

    @Test
    fun statusBandsAndMarginsAreTheSinglePagesInASpread() {
        val s = ReaderSettings(headerLeft = com.ggumtak.readeraplus.settings.StatusItem.CLOCK, footerCenter = com.ggumtak.readeraplus.settings.StatusItem.PAGE)
        val one = LayoutKeys.geometry(s, 1440, 720, density, 0)
        val g = LayoutKeys.geometry(s, 1440, 720, density, 0, 2)
        assertEquals(one.contentTop, g.contentTop)
        assertEquals(one.contentHeight, g.contentHeight)
        assertEquals(one.cutoutTop, g.cutoutTop)
    }

    // ---- keys and layout changes

    private fun key(g: PageGeometry): String = LayoutKeys.key(
        ReaderSettings(), LayoutKeys.parseOptionsFor(ReaderSettings(), com.ggumtak.readeraplus.format.BookFormat.TXT, ""), g, density, "font",
    )

    @Test
    fun theKeyKnowsTheColumnCount() {
        val one = LayoutKeys.geometry(ReaderSettings(), 1440, 720, density)
        val g = LayoutKeys.geometry(ReaderSettings(), 1440, 720, density, 0, 2)
        assertNotEquals(key(one), key(g))
        // The same column width as a single page would have (a 2-column view of 1440 vs a 1-column one 640 wide) still
        // keeps the counts apart.
        val sameBox = g.copy(columns = 1, gutter = 0, pageWidth = g.viewWidth)
        assertNotEquals(key(sameBox), key(g))
        // One column keeps the key it had.
        assertEquals(key(one), key(one.copy()))
    }

    @Test
    fun theSettingAloneIsNoLayoutChangeAndIsNotPartOfTheKey() {
        // The columns come from the setting AND the view: BookSession rebuilds when they change.
        assertFalse(LayoutKeys.layoutChanged(ReaderSettings(), two))
        assertFalse(LayoutKeys.layoutChanged(ReaderSettings(), two, com.ggumtak.readeraplus.format.BookFormat.EPUB))
        val g = LayoutKeys.geometry(ReaderSettings(), 720, 1440, density)
        assertEquals(
            LayoutKeys.keyFor(ReaderSettings(), com.ggumtak.readeraplus.format.BookFormat.TXT, "", g, density, "f"),
            LayoutKeys.keyFor(two, com.ggumtak.readeraplus.format.BookFormat.TXT, "", g, density, "f"),
        )
    }

    // ---- the setting

    @Test
    fun theDefaultIsOnePage() {
        assertEquals(1, ReaderSettings().landscapePages)
        assertEquals(1, ReaderSettings.cleanLandscapePages(0))
        assertEquals(1, ReaderSettings.cleanLandscapePages(7))
        assertEquals(2, ReaderSettings.cleanLandscapePages(2))
    }

    @Test
    fun theSettingSurvivesABackup() {
        val json = SettingsJson.readerToJson(two)
        assertEquals(2, json.getInt("r.landscapePages"))
        assertEquals(2, SettingsJson.readerFromJson(json, ReaderSettings()).landscapePages)
        // A backup without it keeps the base; a wrong value is one page.
        json.remove("r.landscapePages")
        assertEquals(2, SettingsJson.readerFromJson(json, two).landscapePages)
        assertEquals(1, SettingsJson.readerFromJson(json, ReaderSettings()).landscapePages)
        json.put("r.landscapePages", 5)
        assertEquals(1, SettingsJson.readerFromJson(json, two).landscapePages)
    }

    private companion object {
        const val StatusSlotCapacity = 64
    }
}
