package com.ggumtak.readeraplus.ui.settings

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingDefaultsTest {
    @Test
    fun resetRestoresThisPageAndKeepsTxtInvertAndTheStatusBands() {
        val d = ReaderSettings()
        val mine = d.copy(
            fontSizeSp = 24f, fontWeight = 700, lineHeightPct = 200, paragraphSpacingPct = 80, indentPct = 200,
            align = if (d.align == Align.LEFT) Align.JUSTIFY else Align.LEFT, marginLeftDp = 10, marginRightDp = 10,
            epubPublisherStyles = !d.epubPublisherStyles, pageTheme = PageTheme.MARU,
            invert = true, footerCenter = StatusItem.CLOCK, progressBar = !d.progressBar, statusFontSizeSp = 14f,
            txtBlankLines = 3, txtDetectChapters = !d.txtDetectChapters, txtReplaceRules = "a=>b",
        )
        val out = ReadingDefaults.reset(mine)
        // This page's settings are the defaults again.
        assertEquals(d.fontSizeSp, out.fontSizeSp, 0f)
        assertEquals(d.fontWeight, out.fontWeight)
        assertEquals(d.lineHeightPct, out.lineHeightPct)
        assertEquals(d.paragraphSpacingPct, out.paragraphSpacingPct)
        assertEquals(d.indentPct, out.indentPct)
        assertEquals(d.align, out.align)
        assertEquals(d.marginLeftDp, out.marginLeftDp)
        assertEquals(d.epubPublisherStyles, out.epubPublisherStyles)
        // 화면 색 is on this page (스타일): back to 흰 바탕.
        assertEquals(PageTheme.PAPER, out.pageTheme)
        // 흑백 반전, 화면·밝기's status settings and the TXT options are kept.
        assertEquals(true, out.invert)
        assertEquals(StatusItem.CLOCK, out.footerCenter)
        assertEquals(mine.progressBar, out.progressBar)
        assertEquals(14f, out.statusFontSizeSp, 0f)
        assertEquals(3, out.txtBlankLines)
        assertEquals(mine.txtDetectChapters, out.txtDetectChapters)
        assertEquals("a=>b", out.txtReplaceRules)
    }
}
