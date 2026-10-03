package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.DocPosition
import com.ggumtak.readeraplus.reader.ReturnPoints.Chip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnPointsTest {
    private val p10 = DocPosition(0, 1000)
    private val p37 = DocPosition(1, 200)
    private val p512 = DocPosition(4, 50)
    private val p600 = DocPosition(5, 10)
    private val p700 = DocPosition(6, 0)

    @Test
    fun pinSetsThePinnedMark() {
        val r = ReturnPoints()
        r.pin(p10, onMark = false)
        assertEquals(p10, r.mark)
        assertTrue(r.pinned)
        assertNull(r.other)
        assertEquals(Chip.NONE, r.offer)
        assertFalse(r.landed)
    }

    @Test
    fun pinOnThePinnedPageClears() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.pin(p10, onMark = true)
        assertNull(r.mark)
        assertFalse(r.pinned)
        assertNull(r.other)
    }

    @Test
    fun pinWithATemporaryMarkKeepsItAsTheOtherPlace() {      // ★1
        val r = ReturnPoints()
        r.jumped(p512, fromOnMark = false)                    // temporary origin 512
        r.pin(p37, onMark = false)
        assertEquals(p37, r.mark)
        assertTrue(r.pinned)
        assertEquals(p512, r.other)
        assertEquals(Chip.NONE, r.offer)
        assertFalse(r.landed)
    }

    @Test
    fun movingThePinDropsAnOtherOnThisPage() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.jumped(p512, fromOnMark = false)                    // other = 512
        r.manualTurn()
        r.pin(p512, onMark = false)                           // moved to where `other` is
        assertEquals(p512, r.mark)
        assertNull(r.other)
    }

    @Test
    fun jumpWithoutPinRemembersTheOrigin() {
        val r = ReturnPoints()
        r.jumped(p512, fromOnMark = false)
        assertEquals(p512, r.mark)
        assertFalse(r.pinned)
        assertNull(r.other)
        assertEquals(Chip.MARK, r.offer)
        assertEquals(Chip.MARK, r.chainOffer)
        assertTrue(r.landed)
        assertEquals(0, r.turns)
    }

    @Test
    fun jumpFromThePinnedPageKeepsOther() {                   // ★4
        val r = ReturnPoints()
        r.pin(p10, false)
        r.jumped(p512, false)                                 // other = 512 (where you were reading)
        r.useMark(p37, onMark = false)                        // back to the pin: other = 37
        r.jumped(p10, fromOnMark = true)
        assertEquals(p10, r.mark)
        assertEquals(p37, r.other)
        assertEquals(Chip.MARK, r.offer)
    }

    @Test
    fun jumpWhilePinnedElsewhereOffersTheOrigin() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.manualTurn()
        r.jumped(p512, fromOnMark = false)
        assertEquals(p10, r.mark)
        assertEquals(p512, r.other)
        assertEquals(Chip.OTHER, r.offer)
    }

    @Test
    fun aChainKeepsItsFirstOrigin() {                         // ★3
        val r = ReturnPoints()
        r.jumped(p512, false)                                 // seek 1 from 512
        r.jumped(p600, false)                                 // seek 2
        r.jumped(p700, false)                                 // seek 3
        assertEquals(p512, r.mark)
        assertEquals(Chip.MARK, r.offer)
        // One manual turn ends the chain: the next jump remembers a new origin.
        r.manualTurn()
        r.jumped(p37, false)
        assertEquals(p37, r.mark)
        assertEquals(Chip.MARK, r.offer)
    }

    @Test
    fun aJumpAfterCloseRearmsTheChainOffer() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.manualTurn()
        r.jumped(p512, false)
        r.hideChip()                                          // ✕
        assertEquals(Chip.NONE, r.offer)
        assertEquals(p512, r.other)                           // places stay
        r.jumped(p600, false)                                 // still landed: chain
        assertEquals(Chip.OTHER, r.offer)
        assertEquals(p512, r.other)
        assertEquals(0, r.turns)
    }

    @Test
    fun useMarkAndUseOtherToggle() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.manualTurn()
        assertEquals(p10, r.useMark(p512, onMark = false))
        assertEquals(p512, r.other)
        // On the mark: the strip offers 512; using it returns there and keeps 10 as the mark.
        assertNull(r.useMark(p10, onMark = true))
        assertEquals(p512, r.useOther(p10, onMark = true))
        assertNull(r.other)                                   // came from the mark: nothing to offer back but the mark
        assertEquals(p10, r.useMark(p512, onMark = false))
        assertEquals(p512, r.other)
        assertEquals(p512, r.useOther(p37, onMark = false))
        assertEquals(p37, r.other)
        assertEquals(Chip.NONE, r.offer)
        assertFalse(r.landed)
    }

    @Test
    fun useWithNothingReturnsNull() {
        val r = ReturnPoints()
        assertNull(r.useMark(p10, false))
        assertNull(r.useOther(p10, false))
    }

    @Test
    fun manualTurnsHideTheChipAfterTwoAndKeepTheState() {
        val r = ReturnPoints()
        r.jumped(p512, false)
        assertFalse(r.manualTurn())
        assertFalse(r.landed)
        assertEquals(Chip.MARK, r.offer)
        assertTrue(r.manualTurn())
        assertEquals(Chip.NONE, r.offer)
        assertEquals(p512, r.mark)                            // the strip still has it
        assertFalse(r.manualTurn())
    }

    @Test
    fun clearForgetsEverything() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.jumped(p512, false)
        r.clear()
        assertNull(r.mark)
        assertNull(r.other)
        assertFalse(r.pinned)
        assertEquals(Chip.NONE, r.offer)
        assertEquals(Chip.NONE, r.chainOffer)
        assertFalse(r.landed)
        assertEquals(0, r.turns)
    }

    @Test
    fun pinAndClearResetLanded() {
        val r = ReturnPoints()
        r.jumped(p512, false)
        assertTrue(r.landed)
        r.pin(p600, false)
        assertFalse(r.landed)
        r.jumped(p700, false)
        assertTrue(r.landed)
        r.clear()
        assertFalse(r.landed)
    }

    @Test
    fun restorePinnedDemotesATemporaryMark() {
        val r = ReturnPoints()
        r.jumped(p512, false)
        r.restorePinned(p10)
        assertEquals(p10, r.mark)
        assertTrue(r.pinned)
        assertEquals(p512, r.other)
        // Already pinned (pinned this session before the load finished): no-op.
        r.restorePinned(p37)
        assertEquals(p10, r.mark)
    }

    @Test
    fun aPinLoadedAfterAnEarlyJumpStillOffersTheOrigin() {
        val r = ReturnPoints()
        r.jumped(p512, false)                                 // jump before the saved pin arrived
        r.restorePinned(p10)
        assertEquals(p10, r.mark)
        assertEquals(p512, r.other)
        assertEquals(Chip.OTHER, r.offer)                     // the chip still leads back to 512
        r.jumped(p600, false)                                 // chain: same offer
        assertEquals(Chip.OTHER, r.offer)
        assertEquals(p512, r.other)
    }

    @Test
    fun reparsedKeepsOnlyThePin() {
        val r = ReturnPoints()
        r.pin(p10, false)
        r.manualTurn()
        r.jumped(p512, false)
        r.reparsed(p37)
        assertEquals(p37, r.mark)
        assertTrue(r.pinned)
        assertNull(r.other)
        assertEquals(Chip.NONE, r.offer)
        assertFalse(r.landed)

        val t = ReturnPoints()
        t.jumped(p512, false)                                 // temporary only
        t.reparsed(p37)
        assertNull(t.mark)
        assertFalse(t.pinned)

        val n = ReturnPoints()
        n.pin(p10, false)
        n.reparsed(null)
        assertNull(n.mark)
        assertFalse(n.pinned)
    }

    @Test
    fun theOfferSurvivesTheChrome() {                        // ★5
        val r = ReturnPoints()
        r.jumped(p512, false)
        assertTrue(ReturnPoints.chipVisible(r.offer, chromeVisible = false, targetOnScreen = false))
        // The menu opens: the chip view hides, the offer stays; it closes: the chip is back.
        assertFalse(ReturnPoints.chipVisible(r.offer, chromeVisible = true, targetOnScreen = false))
        assertEquals(Chip.MARK, r.offer)
        assertTrue(ReturnPoints.chipVisible(r.offer, chromeVisible = false, targetOnScreen = false))
        // Its place on screen, or no offer: hidden.
        assertFalse(ReturnPoints.chipVisible(r.offer, chromeVisible = false, targetOnScreen = true))
        assertFalse(ReturnPoints.chipVisible(Chip.NONE, chromeVisible = false, targetOnScreen = false))
    }
}
