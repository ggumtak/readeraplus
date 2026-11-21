package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.settings.AppSettings
import com.ggumtak.readeraplus.settings.ReadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The summaries of the settings' main list (the map of the settings: one home for each option). */
class MainRowsTest {
    @Test
    fun turnSummaryNamesScrollOnlyInScrollModeAndCountsAssignedKeys() {
        val d = AppSettings()
        val paged = MainRows.turn(d.copy(readMode = ReadMode.PAGED, volumeKeysTurn = true))
        assertTrue(paged, paged.startsWith("터치: " + TapZoneModel.modeName(d.tapZoneMode)))
        assertTrue(paged, paged.endsWith("볼륨 키"))
        val scroll = MainRows.turn(d.copy(readMode = ReadMode.SCROLL, volumeKeysTurn = false))
        assertTrue(scroll, scroll.startsWith(R3Rows.readMode(ReadMode.SCROLL) + " · 터치: "))
        assertFalse(scroll, scroll.contains("볼륨 키"))
    }

    @Test
    fun librarySummaryIsTheSortAndTheView() {
        val d = AppSettings()
        assertEquals(d.librarySort.label + " · " + d.libraryListMode.label, MainRows.library(d))
    }

    @Test
    fun bookTxtSummarySaysWhetherTheBookHasItsOwnOptions() {
        assertEquals("따로 정함", MainRows.bookTxt(true))
        assertEquals("기본값 따름", MainRows.bookTxt(false))
    }

    @Test
    fun theReaderCallsTheSamePageByOneName() {
        // The reader's 보기 설정 button, its sheet's "보기 설정 전체" and the page's title are one name; the tap action too.
        assertEquals("보기 설정", ReadingPage.TITLE)
        assertEquals("보기 설정 전체", com.ggumtak.readeraplus.reader.extras.ReadingSettingsPopup.ALL_SETTINGS)
        assertEquals("보기 설정", com.ggumtak.readeraplus.settings.TapAction.SETTINGS.label)
        assertEquals("뷰어 설정", MainPage.READER_TITLE)
    }
}
