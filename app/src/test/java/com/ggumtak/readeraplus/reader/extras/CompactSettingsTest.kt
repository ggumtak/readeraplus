package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build 9 fixes in the panels: compact settings popup maths, style presets, "~"-free labels, go-to percent text. */
class CompactSettingsTest {

    // ------------------------------------------------------------------ "~" removal

    @Test
    fun parseAcceptsLabelsWithAndWithoutTilde() {
        for (label in listOf("12 / 3,260", "~12 / ~3,260", "～12 / ～3260", "12 / ~3260")) {
            val p = PageLabel.parse(label)
            assertEquals(label, 12, p.page)
            assertEquals(label, 3260, p.total)
        }
        assertFalse(PageLabel.parse("12 / 3260").estimated)
        assertTrue(PageLabel.parse("~12 / ~3260").estimated)
    }

    @Test
    fun cleanRemovesEveryTilde() {
        assertEquals("12 / 3260", PageLabel.clean("~12 / ~3260"))
        assertEquals("12 / 3260", PageLabel.clean("12 / ～3260"))
        assertEquals("7", PageLabel.clean("~7"))
        assertEquals("3–5", PageLabel.clean("3~5"))
        assertEquals("", PageLabel.clean(null))
        assertEquals("", PageLabel.clean(""))
        val plain = "12 / 3259  ·  챕터 5쪽 남음"
        assertSame(plain, PageLabel.clean(plain))
        for (l in listOf("~1 / ~2", "~12", "a ~ b", "～")) assertFalse(l, PageLabel.clean(l).contains('~') || PageLabel.clean(l).contains('～'))
    }

    @Test
    fun pageOnlyNeverShowsTilde() {
        assertEquals("12", PageLabel.pageOnly("~12 / ~3260"))
        assertEquals("12", PageLabel.pageOnly("12 / 3260"))
        assertEquals("7", PageLabel.pageOnly("~7"))
        assertEquals("쪽", PageLabel.pageOnly("~쪽"))
        assertEquals("", PageLabel.pageOnly(null))
    }

    // ------------------------------------------------------------------ go-to dialog text

    @Test
    fun goToPercentMatchesTheFooter() {
        for (i in 0..1000) {
            val f = i / 1000f
            assertEquals("${ReaderFormat.percent(f)}%", GoToText.percent(f))
        }
        assertEquals("${ReaderFormat.percent(0f)}%", GoToText.percent(Float.NaN))
    }

    @Test
    fun goToInfoText() {
        val pct = GoToText.percent(0.52f)
        assertEquals("현재 12 / 3259쪽  ·  $pct", GoToText.info(12, 3259, 0.52f, pagesKnown = true))
        assertEquals("현재 $pct", GoToText.info(-1, -1, 0.52f, pagesKnown = true))
        val counting = GoToText.info(12, 3260, 0.52f, pagesKnown = false)
        assertTrue(counting.startsWith("현재 12 / 3260쪽  ·  $pct\n"))
        assertFalse(counting.contains('~'))
    }

    // popup size maths: PopupGeometryTest

    // ------------------------------------------------------------------ style presets

    @Test
    fun defaultsAreMaruViewerStyle() {
        val d = ReaderSettings()
        assertEquals(StylePreset.MARU, StyleChoice.selected(d))
        assertEquals("nanummyeongjo", d.fontId)
        assertEquals(Align.LEFT, d.align)
        assertEquals(LineBreakMode.WORD, d.lineBreak)
        assertEquals(0, d.indentPct)
    }

    @Test
    fun selectedPresetFollowsTypographyOnly() {
        val d = ReaderSettings()
        for (p in StylePreset.entries) {
            val s = p.applyTo(d)
            assertEquals(p, StyleChoice.selected(s))
            // Exactly one preset is marked.
            assertEquals(1, StylePreset.entries.count { it.matches(s) })
            // Font size, margins and status bar are not part of a style.
            assertEquals(p, StyleChoice.selected(s.copy(fontSizeSp = 24f, marginLeftDp = 30, footerLeft = StatusItem.NONE)))
        }
        // A typography tweak leaves every preset ("사용자 설정": nothing inverted).
        assertNull(StyleChoice.selected(d.copy(lineHeightPct = 205)))
        assertNull(StyleChoice.selected(StylePreset.RIDI.applyTo(d).copy(align = Align.LEFT)))
        // Applying a preset keeps non-typography fields.
        val custom = d.copy(fontSizeSp = 23.5f, marginTopDp = 40, txtBlankLines = 2, invert = true)
        val ridi = StylePreset.RIDI.applyTo(custom)
        assertEquals(23.5f, ridi.fontSizeSp, 0f)
        assertEquals(40, ridi.marginTopDp)
        assertEquals(2, ridi.txtBlankLines)
        assertTrue(ridi.invert)
    }
}
