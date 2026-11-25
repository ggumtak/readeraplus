package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.reader.BackOrder.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackOrderTest {
    private fun next(
        endPanel: Boolean = false,
        selection: Boolean = false,
        chrome: Boolean = false,
        tts: Boolean = false,
        searchBar: Boolean = false,
    ): Step = BackOrder.next(endPanel, selection, chrome, tts, searchBar)

    @Test fun nothingOpenLeavesTheBook() {
        assertEquals(Step.EXIT, next())
    }

    @Test fun eachAloneIsClosed() {
        assertEquals(Step.END_PANEL, next(endPanel = true))
        assertEquals(Step.SELECTION, next(selection = true))
        assertEquals(Step.CHROME, next(chrome = true))
        assertEquals(Step.TTS, next(tts = true))
        assertEquals(Step.SEARCH_BAR, next(searchBar = true))
    }

    @Test fun barsCloseBeforeTtsStopsOrTheSearchBarCloses() {
        assertEquals(Step.CHROME, next(chrome = true, tts = true))
        assertEquals(Step.CHROME, next(chrome = true, searchBar = true))
        assertEquals(Step.CHROME, next(chrome = true, tts = true, searchBar = true))
    }

    @Test fun ttsStopsBeforeTheSearchBarCloses() {
        assertEquals(Step.TTS, next(tts = true, searchBar = true))
    }

    @Test fun endPanelAndSelectionStayAboveTheBars() {
        assertEquals(Step.END_PANEL, next(endPanel = true, selection = true, chrome = true, tts = true, searchBar = true))
        assertEquals(Step.SELECTION, next(selection = true, chrome = true, tts = true, searchBar = true))
    }

    /** 검색 결과 목록 hid the bar and left its highlight: closing the bars (or anything else in the book) takes it too. */
    @Test fun aHighlightLeftByAHiddenSearchBarGoesWithABackThatStays() {
        val step = next(chrome = true, searchBar = false)
        assertEquals(Step.CHROME, step)
        assertTrue(BackOrder.dropsSearchHighlight(step, searchBar = false))
        for (s in listOf(Step.END_PANEL, Step.SELECTION, Step.TTS)) assertTrue(BackOrder.dropsSearchHighlight(s, false))
        // Leaving the book clears everything anyway.
        assertFalse(BackOrder.dropsSearchHighlight(Step.EXIT, searchBar = false))
    }

    @Test fun aShownSearchBarKeepsItsHighlightUntilItsOwnStep() {
        for (s in listOf(Step.END_PANEL, Step.SELECTION, Step.CHROME, Step.TTS, Step.SEARCH_BAR)) {
            assertFalse(BackOrder.dropsSearchHighlight(s, searchBar = true))
        }
    }

    /** Pressing BACK repeatedly with everything open closes one thing per press, top first. */
    @Test fun repeatedBackWalksTheWholeOrder() {
        val open = booleanArrayOf(true, true, true, true, true)
        val seen = ArrayList<Step>()
        while (true) {
            val s = BackOrder.next(open[0], open[1], open[2], open[3], open[4])
            seen += s
            if (s == Step.EXIT) break
            open[s.ordinal] = false
        }
        assertEquals(
            listOf(Step.END_PANEL, Step.SELECTION, Step.CHROME, Step.TTS, Step.SEARCH_BAR, Step.EXIT),
            seen,
        )
    }
}
