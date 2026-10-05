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

    /** At 1, a seek to 1749; read there; a seek to 150 (the user's ReadEra screenshots). */
    private fun readEraBook(): ReturnHistory {
        val h = ReturnHistory()
        h.jumped(at(1), on(1))
        h.manualTurn()                                        // reading on 1749 (a page on and back)
        h.manualTurn()
        h.jumped(at(1749), on(1749))
        return h
    }

    @Test
    fun theUsersTwoScreenshots() {
        val h = readEraBook()
        assertEquals(listOf(at(1), at(1749)), h.back)
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
    }

    @Test
    fun scrubbingKeepsTheFirstOrigin() {                     // ★3
        val h = ReturnHistory()
        assertTrue(h.jumped(at(10), on(10)))                  // seek 1 from 10
        assertFalse(h.jumped(at(500), on(500)))               // seek 2: no place changes (nothing to store)
        h.hideChip()                                          // ✕ between them
        h.jumped(at(600), on(600))                            // seek 3: the chip is offered again
        assertEquals(listOf(at(10)), h.back)
        assertTrue(h.offer)
        assertEquals(0, h.turns)
        assertEquals("10쪽으로 | -", h.row(700))
        // One manual turn ends the chain: the next jump remembers a new origin.
        h.manualTurn()
        h.jumped(at(701), on(701))
        assertEquals(listOf(at(10), at(701)), h.back)
    }

    @Test
    fun aChainAfterGoingBackStillCutsNothingMore() {
        val h = readEraBook()
        h.goBack(at(150), on(150))                            // on 1749: forward [150]; a use ends any chain
        h.jumped(at(1749), on(1749))                          // seek: forward cut, origin 1749
        h.jumped(at(400), on(400))                            // scrubbing on: no 400
        assertEquals(listOf(at(1), at(1749)), h.back)
        assertTrue(h.forward.isEmpty())
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
    }

    @Test
    fun aPlaceOnTheTopsPageIsNotPushedTwice() {
        val h = ReturnHistory()
        h.pin(at(10), on(10))
        assertFalse(h.jumped(DocPosition(0, 1050), on(10)))   // the same page, another offset
        assertEquals(listOf(at(10)), h.back)
        assertTrue(h.offer)
        // Read on by hand from 50 to forward's top 90: no right item there, and going back keeps one entry for 90.
        val g = ReturnHistory()
        g.restore(listOf(at(1), at(50)), listOf(at(90)))
        assertEquals("50쪽으로 | -", g.row(90))
        assertEquals(at(50), g.goBack(at(90), on(90)))
        assertEquals(listOf(at(90)), g.forward)
        assertEquals("1쪽으로 | 90쪽으로", g.row(50))
        // Going forward from the page that is back's top (pinned here) keeps one entry for it as well.
        val p = ReturnHistory()
        p.restore(listOf(at(1), at(50)), listOf(at(90)))
        assertEquals("- | 90쪽으로", p.row(50))
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
        assertEquals("", h.row(8))
        h.pin(at(8), on(8))                                   // the filled pin again: 고정 해제
        assertEquals(listOf(at(3)), h.back)
        assertEquals("3쪽으로 | -", h.row(8))
        assertFalse(h.pinnedHere(on(8)))
    }

    @Test
    fun pinKeepsTheForwardListAndEndsAChain() {
        val h = readEraBook()
        h.goBack(at(150), on(150))                            // on 1749: back [1], forward [150]
        h.pin(at(1749), on(1749))
        assertEquals(listOf(at(1), at(1749)), h.back)
        assertEquals(listOf(at(150)), h.forward)
        assertEquals("- | 150쪽으로", h.row(1749))
        val c = ReturnHistory()
        c.jumped(at(10), on(10))
        assertTrue(c.landed)
        c.pin(at(500), on(500))                               // pinned where the seek landed
        assertFalse(c.landed)
        assertFalse(c.offer)
        c.jumped(at(500), on(500))                            // its top is this page: kept once
        assertEquals(listOf(at(10), at(500)), c.back)
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
    }

    @Test
    fun clearEmptiesBothLists() {
        val h = readEraBook()
        h.goBack(at(150), on(150))
        h.clear()
        assertTrue(h.back.isEmpty())
        assertTrue(h.forward.isEmpty())
        assertEquals("", h.row(1749))
        assertFalse(h.offer)
        assertFalse(h.landed)
        assertEquals(0, h.turns)
        assertNull(h.goBack(at(1749), on(1749)))
        assertNull(h.goForward(at(1749), on(1749)))
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
    fun restorePutsTheStoredPlacesUnderThisSessions() {
        val fresh = ReturnHistory()
        fresh.restore(listOf(at(1), at(1749)), listOf(at(150)))
        assertEquals(listOf(at(1), at(1749)), fresh.back)
        assertEquals(listOf(at(150)), fresh.forward)
        // A jump before the load: its origin stays on top and the stored forward list is cut, as the jump would have.
        val early = ReturnHistory()
        early.jumped(at(40), on(40))
        early.restore(listOf(at(1), at(1749)), listOf(at(150)))
        assertEquals(listOf(at(1), at(1749), at(40)), early.back)
        assertTrue(early.forward.isEmpty())
        assertTrue(early.offer)                               // the chip still leads back to 40
        // Reopened where the stored history's newest place is: kept once.
        val same = ReturnHistory()
        same.jumped(at(1749), on(1749))
        same.restore(listOf(at(1), at(1749)), emptyList())
        assertEquals(listOf(at(1), at(1749)), same.back)
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
        // Two places that land on the same spot are kept once.
        val d = ReturnHistory()
        d.restore(listOf(at(1), at(2), at(3)), emptyList())
        d.reparsed { _, _ -> at(7) }
        assertEquals(listOf(at(7)), d.back)
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
