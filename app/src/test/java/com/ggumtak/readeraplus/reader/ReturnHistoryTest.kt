package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U §3.2: the return history, as ReadEra's row (user, 2026-10-05: "이전이 없으면 왼쪽이 사라지고 이전이 있으면 왼쪽이
 * 생기는 방식 … 오른쪽은 다음이 있으면 생기고 없으면 없고"). A page is 100 chars of section 0 here; [on] is the host's "is
 * on the current page".
 */
class ReturnHistoryTest {
    private fun at(page: Int) = DocPosition(0, page * 100)
    private fun on(page: Int): (DocPosition) -> Boolean = { it.section == 0 && it.offset / 100 == page }

    /** The row on [page]: "left | right" with "-" for an invisible side, "" when the row does not show. */
    private fun ReturnHistory.row(page: Int): String {
        if (!rowShown(on(page))) return ""
        val l = leftPlace(on(page))?.let { ReturnHistory.label(it.offset / 100) } ?: "-"
        val r = rightPlace(on(page))?.let { ReturnHistory.label(it.offset / 100) } ?: "-"
        return "$l | $r"
    }

    /** At 1, a seek to 1749; from there, without turning a page, a seek to 150 (the user's ReadEra screenshots). */
    private fun readEraBook(): ReturnHistory {
        val h = ReturnHistory()
        h.jumped(at(1), on(1))
        h.jumped(at(1749), on(1749))
        return h
    }

    @Test
    fun theUsersTwoScreenshots() {
        val h = readEraBook()
        assertEquals(listOf(at(1), at(1749)), h.back)        // each seek's origin is a place, turned or not
        assertEquals("1749쪽으로 | -", h.row(150))            // nothing ahead: no right item
        assertEquals(at(1749), h.goBack(at(150), on(150)))
        // Screenshot 1, on 1749: "< 1 페이지로 | 지우기 | 150 페이지로 >".
        assertEquals("1쪽으로 | 150쪽으로", h.row(1749))
        assertEquals(at(1), h.goBack(at(1749), on(1749)))
        // Screenshot 2, on 1: "지우기 | 1749 페이지로 >" (no left item; 지우기 stays in the middle).
        assertEquals("- | 1749쪽으로", h.row(1))
        assertEquals(listOf(at(150), at(1749)), h.forward)   // the nearest last
        assertTrue(h.back.isEmpty())
    }

    @Test
    fun everyRememberedJumpKeepsItsOrigin() {
        // Seek, seek again with no page turned (the menu open or closed between them): both origins stay.
        val h = ReturnHistory()
        h.jumped(at(1), on(1))
        assertTrue(h.jumped(at(1749), on(1749)))              // stored
        assertEquals(listOf(at(1), at(1749)), h.back)
        // A TOC jump, then a footnote link from there, no turn between: the middle place stays as well.
        h.jumped(at(150), on(150))
        assertEquals(listOf(at(1), at(1749), at(150)), h.back)
        assertEquals("150쪽으로 | -", h.row(900))
    }

    @Test
    fun forwardThenBack() {
        val h = readEraBook()
        h.goBack(at(150), on(150))
        h.goBack(at(1749), on(1749))                          // on 1: forward [150, 1749]
        assertEquals(at(1749), h.goForward(at(1), on(1)))
        assertEquals("1쪽으로 | 150쪽으로", h.row(1749))
        assertEquals(at(150), h.goForward(at(1749), on(1749)))
        assertEquals("1749쪽으로 | -", h.row(150))
        assertNull(h.goForward(at(150), on(150)))             // nothing ahead
        assertEquals(at(1749), h.goBack(at(150), on(150)))
        assertEquals("1쪽으로 | 150쪽으로", h.row(1749))
        assertFalse(h.offer)
        assertFalse(h.landed)
    }

    @Test
    fun aNewJumpAfterGoingBackCutsTheForwardList() {
        val h = readEraBook()
        h.goBack(at(150), on(150))                            // on 1749: back [1], forward [150]
        h.jumped(at(1749), on(1749))                          // TOC to 300
        assertEquals(listOf(at(1), at(1749)), h.back)
        assertTrue(h.forward.isEmpty())
        assertEquals("1749쪽으로 | -", h.row(300))
        assertTrue(h.offer)
        h.jumped(at(300), on(300))                            // and on to 400, no turn between
        assertEquals(listOf(at(1), at(1749), at(300)), h.back)
        assertEquals(at(1749), h.chipPlace())                 // the chain began after the use
    }

    @Test
    fun theChipOffersTheFirstOriginOfAChain() {              // ★3, the chip only
        val h = ReturnHistory()
        assertTrue(h.jumped(at(10), on(10)))                  // seek 1 from 10
        assertTrue(h.jumped(at(500), on(500)))                // seek 2: 500 is a place too
        h.hideChip()                                          // ✕ between them
        h.jumped(at(600), on(600))                            // seek 3: the chip is offered again
        assertEquals(listOf(at(10), at(500), at(600)), h.back)
        assertTrue(h.offer)
        assertEquals(0, h.turns)
        assertEquals(at(10), h.chipPlace())                   // where the reading was
        assertEquals("600쪽으로 | -", h.row(700))              // the row steps back one place at a time
        // The chip goes there as three ‹ taps would: the page left and the places passed are ahead, the nearest last.
        assertEquals(at(10), h.chipBack(at(700), on(700)))
        assertTrue(h.back.isEmpty())
        assertEquals(listOf(at(700), at(600), at(500)), h.forward)
        assertEquals("- | 500쪽으로", h.row(10))
        assertFalse(h.offer)
        assertFalse(h.landed)
        // One manual turn ends the chain: the chip keeps its place until it hides, the next jump starts a new chain.
        val g = ReturnHistory()
        g.jumped(at(10), on(10))
        g.jumped(at(500), on(500))
        g.manualTurn()
        assertEquals(at(10), g.chipPlace())
        g.jumped(at(501), on(501))
        assertEquals(listOf(at(10), at(500), at(501)), g.back)
        assertEquals(at(501), g.chipPlace())
        assertEquals(at(501), g.chipBack(at(900), on(900)))   // one step, like ‹
        assertEquals(listOf(at(900)), g.forward)
    }

    @Test
    fun bothListsKeepTheirNewestPlaces() {
        val h = ReturnHistory()
        for (p in 0 until 25) {
            h.jumped(at(p * 10), on(p * 10))
            h.manualTurn()
        }
        assertEquals(ReturnHistory.MAX, h.back.size)
        assertEquals(at(50), h.back.first())                  // 0..40 dropped, the oldest first
        assertEquals(at(240), h.back.last())
        val f = ReturnHistory()
        f.restore(emptyList(), (0 until 25).map { at(it) })
        assertEquals(ReturnHistory.MAX, f.forward.size)
        assertEquals(at(5), f.forward.first())
        assertEquals(at(24), f.forward.last())                // the nearest stays
        // One chain longer than the list: its origin is gone, the chip offers its oldest place kept.
        val c = ReturnHistory()
        for (p in 0 until 25) c.jumped(at(p * 10), on(p * 10))
        assertEquals(ReturnHistory.MAX, c.back.size)
        assertEquals(c.back.first(), c.chipPlace())
    }

    @Test
    fun aPlaceOnTheTopsPageIsNotPushedTwice() {
        val h = ReturnHistory()
        h.pin(at(10), on(10))
        assertFalse(h.jumped(DocPosition(0, 1050), on(10)))   // the same page, another offset
        assertEquals(listOf(at(10)), h.back)
        assertTrue(h.offer)
        assertTrue(h.pinnedHere(on(10)))                      // still the pin
        // Read on by hand from 50 to forward's top 90: no right item there, and going back keeps one entry for 90.
        val g = ReturnHistory()
        g.restore(listOf(at(1), at(50)), listOf(at(90)))
        assertEquals("50쪽으로 | -", g.row(90))
        assertEquals(at(50), g.goBack(at(90), on(90)))
        assertEquals(listOf(at(90)), g.forward)
        assertEquals("1쪽으로 | 90쪽으로", g.row(50))
        // Going forward from the page that is back's top keeps one entry for it; the older place shows meanwhile.
        val p = ReturnHistory()
        p.restore(listOf(at(1), at(50)), listOf(at(90)))
        assertEquals("1쪽으로 | 90쪽으로", p.row(50))
        assertEquals(at(90), p.goForward(at(50), on(50)))
        assertEquals(listOf(at(1), at(50)), p.back)
        assertEquals("50쪽으로 | -", p.row(90))
    }

    @Test
    fun pinSavesThisPageAndUnpinsOnIt() {
        val h = ReturnHistory()
        h.pin(at(3), on(3))
        assertEquals(listOf(at(3)), h.back)
        assertTrue(h.pinnedHere(on(3)))                       // the filled pin ("고정 해제")
        assertEquals("", h.row(3))                            // nothing to go to: no row, never a dead "3쪽"
        assertFalse(h.offer)
        // Five pages on: the row offers the pinned page; the pin is an outline again.
        assertFalse(h.pinnedHere(on(8)))
        assertEquals("3쪽으로 | -", h.row(8))
        h.pin(at(8), on(8))
        assertEquals(listOf(at(3), at(8)), h.back)
        assertEquals("3쪽으로 | -", h.row(8))                  // the way back to 3 stays
        assertTrue(h.pinnedHere(on(8)))
        h.pin(at(8), on(8))                                   // the filled pin again: 고정 해제
        assertEquals(listOf(at(3)), h.back)
        assertEquals("3쪽으로 | -", h.row(8))
        assertFalse(h.pinnedHere(on(8)))
    }

    @Test
    fun pinningNeverHidesTheWayBack() {
        // Screenshot 1's state on 1749 ("‹ 1 · 지우기 · 150 ›"), then the pin: the row stays, the pin fills.
        val h = readEraBook()
        h.goBack(at(150), on(150))
        h.pin(at(1749), on(1749))
        assertEquals(listOf(at(1), at(1749)), h.back)
        assertEquals(listOf(at(150)), h.forward)
        assertEquals("1쪽으로 | 150쪽으로", h.row(1749))
        assertTrue(h.pinnedHere(on(1749)))
        // "‹ 1쪽으로": the pin goes ahead with this page (it is this page).
        assertEquals(at(1), h.goBack(at(1749), on(1749)))
        assertEquals(listOf(at(150), at(1749)), h.forward)
        assertTrue(h.back.isEmpty())
        assertEquals("- | 1749쪽으로", h.row(1))
        // Reopened on a pinned page with older places: they show.
        val r = ReturnHistory()
        r.restore(listOf(at(1), at(50)), emptyList(), storedPin = true)
        assertEquals("1쪽으로 | -", r.row(50))
        assertTrue(r.pinnedHere(on(50)))
        // Two places on one page (a re-parse, or an origin at another offset): the older place shows and is reached.
        val two = ReturnHistory()
        two.restore(listOf(at(1), DocPosition(0, 5010), DocPosition(0, 5050)), emptyList())
        assertEquals("1쪽으로 | -", two.row(50))
        assertEquals(at(1), two.goBack(at(50), on(50)))
        assertEquals(listOf(at(50)), two.forward)
        // Paged back by hand onto a jump's origin: the place before it shows.
        val o = ReturnHistory()
        o.jumped(at(5), on(5))
        o.manualTurn()
        o.jumped(at(10), on(10))                              // read on 5, then TOC from 10 to 11
        assertEquals("5쪽으로 | -", o.row(10))                 // back on 10 by hand
    }

    @Test
    fun theFilledPinMeansThePinSavedThisPage() {
        // A jump's origin on this page (paged back by hand) is no pin: the outline; a tap makes it one.
        val h = ReturnHistory()
        h.jumped(at(10), on(10))                              // TOC from 10 to 11
        assertFalse(h.pinnedHere(on(10)))                     // a turn back to 10
        h.pin(at(10), on(10))
        assertEquals(listOf(at(10)), h.back)                  // the same entry, now the pin
        assertTrue(h.pinnedHere(on(10)))
        h.pin(at(10), on(10))                                 // 고정 해제
        assertTrue(h.back.isEmpty())
        // A newer place on top replaces the pin's state.
        val g = ReturnHistory()
        g.pin(at(3), on(3))
        g.jumped(at(8), on(8))
        assertFalse(g.pinTop)
        assertFalse(g.pinnedHere(on(3)))
    }

    @Test
    fun pinningForwardsTopKeepsOnePlaceForIt() {
        // Pin 3, read to 8, "‹ 3쪽으로" (forward [8]), read by hand back to 8 and pin: 8 is back's, not on both sides.
        val h = ReturnHistory()
        h.pin(at(3), on(3))
        assertEquals(at(3), h.goBack(at(8), on(8)))
        assertEquals(listOf(at(8)), h.forward)
        h.pin(at(8), on(8))
        assertEquals(listOf(at(8)), h.back)
        assertTrue(h.forward.isEmpty())
        assertEquals("8쪽으로 | -", h.row(9))
    }

    @Test
    fun pinKeepsTheForwardListAndEndsAChain() {
        val h = readEraBook()
        h.goBack(at(150), on(150))                            // on 1749: back [1], forward [150]
        h.pin(at(1749), on(1749))
        assertEquals(listOf(at(150)), h.forward)
        val c = ReturnHistory()
        c.jumped(at(10), on(10))
        assertTrue(c.landed)
        c.pin(at(500), on(500))                               // pinned where the seek landed
        assertFalse(c.landed)
        assertFalse(c.offer)
        c.jumped(at(500), on(500))                            // its top is this page: kept once
        assertEquals(listOf(at(10), at(500)), c.back)
        assertEquals(at(500), c.chipPlace())                  // a new chain, from the pinned page
    }

    @Test
    fun goingBackFromThePinnedPageKeepsTheWayForward() {
        // The CI flow (13b–13d): pin 3, five turns, "‹ 3쪽으로" → on 3 "8쪽으로 ›"; "8쪽으로 ›" → on 8 "‹ 3쪽으로".
        val h = ReturnHistory()
        h.pin(at(3), on(3))
        for (i in 1..5) h.manualTurn()
        assertEquals(at(3), h.goBack(at(8), on(8)))
        assertEquals("- | 8쪽으로", h.row(3))
        assertFalse(h.pinnedHere(on(3)))                      // used: the outline pin
        assertEquals(at(8), h.goForward(at(3), on(3)))
        assertEquals("3쪽으로 | -", h.row(8))
        assertFalse(h.pinnedHere(on(3)))
    }

    @Test
    fun clearEmptiesBothLists() {
        val h = readEraBook()
        h.goBack(at(150), on(150))
        h.pin(at(1749), on(1749))
        h.clear()
        assertTrue(h.back.isEmpty())
        assertTrue(h.forward.isEmpty())
        assertEquals("", h.row(1749))
        assertFalse(h.offer)
        assertFalse(h.landed)
        assertFalse(h.pinTop)
        assertEquals(0, h.turns)
        assertNull(h.goBack(at(1749), on(1749)))
        assertNull(h.goForward(at(1749), on(1749)))
        assertNull(h.chipBack(at(1749), on(1749)))
        assertNull(h.chipPlace())
    }

    @Test
    fun theChipHidesAfterTwoManualTurnsAndKeepsThePlaces() {
        val h = ReturnHistory()
        h.jumped(at(512), on(512))
        assertTrue(h.offer)
        assertFalse(h.manualTurn())
        assertFalse(h.landed)
        assertTrue(h.offer)
        assertTrue(h.manualTurn())
        assertFalse(h.offer)
        assertEquals(listOf(at(512)), h.back)                 // the row still has it
        assertFalse(h.manualTurn())
    }

    @Test
    fun theChipHidesWhenTheHistoryIsUsed() {
        val h = ReturnHistory()
        h.jumped(at(512), on(512))
        h.goBack(at(37), on(37))
        assertFalse(h.offer)
        h.jumped(at(512), on(512))
        h.hideChip()                                          // ✕
        assertFalse(h.offer)
        assertEquals(listOf(at(512)), h.back)
    }

    @Test
    fun theOfferSurvivesTheChrome() {                        // ★5
        val h = ReturnHistory()
        h.jumped(at(512), on(512))
        assertTrue(ReturnHistory.chipVisible(h.offer, chromeVisible = false, targetOnScreen = false))
        // The menu opens: the chip view hides, the offer stays; it closes: the chip is back.
        assertFalse(ReturnHistory.chipVisible(h.offer, chromeVisible = true, targetOnScreen = false))
        assertTrue(h.offer)
        assertTrue(ReturnHistory.chipVisible(h.offer, chromeVisible = false, targetOnScreen = false))
        // Its place on screen, or no offer: hidden.
        assertFalse(ReturnHistory.chipVisible(h.offer, chromeVisible = false, targetOnScreen = true))
        assertFalse(ReturnHistory.chipVisible(false, chromeVisible = false, targetOnScreen = false))
    }

    @Test
    fun aUseIsUndoneByACopy() {
        // ReturnNav keeps the history from before a use whose page shows later; a failed layout puts it back.
        val h = readEraBook()
        val before = ReturnHistory()
        before.copyFrom(h)
        h.goBack(at(150), on(150))
        h.copyFrom(before)
        assertEquals(listOf(at(1), at(1749)), h.back)
        assertTrue(h.forward.isEmpty())
        assertTrue(h.offer)
        assertTrue(h.landed)
        assertEquals(at(1), h.chipPlace())
        assertEquals("1749쪽으로 | -", h.row(150))
    }

    @Test
    fun restorePutsTheStoredPlacesUnderThisSessions() {
        val fresh = ReturnHistory()
        fresh.restore(listOf(at(1), at(1749)), listOf(at(150)))
        assertEquals(listOf(at(1), at(1749)), fresh.back)
        assertEquals(listOf(at(150)), fresh.forward)
        assertFalse(fresh.pinTop)
        // A jump before the load: its origin stays on top and the stored forward list is cut, as the jump would have.
        val early = ReturnHistory()
        early.jumped(at(40), on(40))
        early.restore(listOf(at(1), at(1749)), listOf(at(150)), storedPin = true)
        assertEquals(listOf(at(1), at(1749), at(40)), early.back)
        assertTrue(early.forward.isEmpty())
        assertTrue(early.offer)                               // the chip still leads back to 40
        assertEquals(at(40), early.chipPlace())
        assertFalse(early.pinTop)                             // 40 is a jump's origin, not the stored pin
        // Reopened where the stored history's newest place is (a pin): kept once, still the pin.
        val same = ReturnHistory()
        same.jumped(at(1749), on(1749))
        same.restore(listOf(at(1), at(1749)), emptyList(), storedPin = true)
        assertEquals(listOf(at(1), at(1749)), same.back)
        assertTrue(same.pinTop)
        assertEquals(at(1749), same.chipPlace())
    }

    @Test
    fun reparsedMapsEveryPlaceAndDropsTheLost() {
        val h = readEraBook()
        h.goBack(at(150), on(150))                            // back [1], forward [150]
        h.pin(at(1749), on(1749))                             // back [1, 1749]
        // Index order: back then forward. 1749 is lost; 1 and 150 move by +1 char.
        h.reparsed { i, p -> if (i == 1) null else DocPosition(p.section, p.offset + 1) }
        assertEquals(listOf(DocPosition(0, 101)), h.back)
        assertEquals(listOf(DocPosition(0, 15001)), h.forward)
        assertFalse(h.offer)
        assertFalse(h.landed)
        assertFalse(h.pinnedHere(on(1)))                      // the pin's place is gone: 1 is no pin
        // Two places that land on the same spot are kept once.
        val d = ReturnHistory()
        d.restore(listOf(at(1), at(2), at(3)), emptyList())
        d.reparsed { _, _ -> at(7) }
        assertEquals(listOf(at(7)), d.back)
        // A pinned place that moves stays the pin.
        val p = ReturnHistory()
        p.pin(at(5), on(5))
        p.reparsed { _, q -> DocPosition(q.section, q.offset + 1) }
        assertTrue(p.pinnedHere(on(5)))
    }

    @Test
    fun labelsSayPagesWithTheUnitAttached() {
        assertEquals("3쪽으로", ReturnHistory.label(3))
        assertEquals("1749쪽으로", ReturnHistory.label(1749))
        val h = ReturnHistory()
        assertEquals("", h.row(1))                            // empty history: no row
        assertFalse(h.pinnedHere(on(1)))
    }
}
