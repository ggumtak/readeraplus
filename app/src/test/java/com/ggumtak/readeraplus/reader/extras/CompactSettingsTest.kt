package com.ggumtak.readeraplus.reader.extras

import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.reader.ReaderFormat
import com.ggumtak.readeraplus.settings.PageTheme
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
    fun retainKeepsTheShownPageOnlyWhileTheCountRuns() {
        // The exact page replaces whatever was shown.
        assertEquals("12", PageLabel.retain("12", "7", counting = true))
        assertEquals("12", PageLabel.retain(" 12 ", "", counting = false))
        // While counting (no exact page yet) the label keeps its last page instead of going blank.
        assertEquals("7", PageLabel.retain("", "7", counting = true))
        assertEquals("7", PageLabel.retain(null, "7", counting = true))
        assertEquals("7", PageLabel.retain("-", "7", counting = true))
        // Nothing to keep, or no count under way (a stopped count): blank.
        assertEquals("", PageLabel.retain("", "", counting = true))
        assertEquals("", PageLabel.retain("", "-", counting = true))
        assertEquals("", PageLabel.retain("", "7", counting = false))
    }

    @Test
    fun cleanRemovesEveryTilde() {
        assertEquals("12 / 3260", PageLabel.clean("~12 / ~3260"))
        assertEquals("12 / 3260", PageLabel.clean("12 / ～3260"))
        assertEquals("7", PageLabel.clean("~7"))
        assertEquals("3–5", PageLabel.clean("3~5"))
        assertEquals("", PageLabel.clean(null))
        assertEquals("", PageLabel.clean(""))
        val plain = "12 / 3259 · 2/32"
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
        assertEquals("현재 12 / 3259쪽 · $pct", GoToText.info(12, 3259, 0.52f, pagesKnown = true))
        assertEquals("현재 $pct", GoToText.info(-1, -1, 0.52f, pagesKnown = true))
        val counting = GoToText.info(12, 3260, 0.52f, pagesKnown = false)
        // No estimated page while counting (2026-10-05): only the percent and the note.
        assertEquals("현재 $pct\n쪽수 계산 중 · %로 이동하세요", counting)
        assertFalse(counting.contains('~'))
        assertEquals("현재 $pct\n쪽수 확인 불가 · %로 이동하세요",
            GoToText.info(12, 3260, 0.52f, pagesKnown = false, pending = "쪽수 확인 불가"))
    }

    // popup size maths: PopupGeometryTest

    // ------------------------------------------------------------------ style presets

    @Test
    fun defaultsKeepTheEarlierWebNovelTypographyOnWhite() {
        val d = ReaderSettings()
        // 웹소설 became the MaruViewer page (2026-10-04); the defaults stayed e-ink first: no preset, the row says "기본".
        assertNull(StyleChoice.selected(d))
        assertTrue(StyleChoice.isDefault(d))
        assertEquals(PageTheme.PAPER, d.pageTheme)
        assertEquals("nanummyeongjo", d.fontId)
        assertEquals(400, d.fontWeight)
        assertEquals(200, d.lineHeightPct)
        assertEquals(100, d.paragraphSpacingPct)
        assertEquals(Align.LEFT, d.align)
        assertEquals(LineBreakMode.WORD, d.lineBreak)
        assertEquals(0, d.indentPct)
    }

    @Test
    fun webNovelPresetIsTheMaruViewerPage() {
        val d = ReaderSettings(fontSizeSp = 18f, marginLeftDp = 30, marginRightDp = 30)
        val m = StylePreset.MARU.applyTo(d)
        assertEquals("웹소설", StylePreset.MARU.label)
        assertTrue(StylePreset.MARU.description.startsWith("마루뷰어 화면 · 나눔명조"))
        // Measured on the MaruViewer screenshot: 나눔명조 Regular, 2 em line pitch, one empty line between paragraphs,
        // ragged right with breaks between words, no indent, default letter spacing, the dark grey page.
        assertEquals("nanummyeongjo", m.fontId)
        assertEquals(400, m.fontWeight)
        assertEquals(200, m.lineHeightPct)
        assertEquals(200, m.paragraphSpacingPct)
        assertEquals(0, m.indentPct)
        assertEquals(0, m.letterSpacingPm)
        assertEquals(Align.LEFT, m.align)
        assertEquals(LineBreakMode.WORD, m.lineBreak)
        assertEquals(PageTheme.MARU, m.pageTheme)
        // The user's font size and margins stay; 흑백 반전 is not a preset's business.
        assertEquals(18f, m.fontSizeSp, 0f)
        assertEquals(30, m.marginLeftDp)
        assertFalse(m.invert)
        assertTrue(StylePreset.MARU.applyTo(d.copy(invert = true)).invert)
        // The other presets keep their typography on the white page.
        assertEquals(PageTheme.PAPER, StylePreset.RIDI.applyTo(m).pageTheme)
        assertEquals(PageTheme.PAPER, StylePreset.BOOK.applyTo(m).pageTheme)
        assertEquals(StylePreset.RIDI.applyTo(d), StylePreset.RIDI.applyTo(m))
    }

    @Test
    fun defaultLookIsTheDefaultsTypographyAndPageColours() {
        val d = ReaderSettings()
        // What a style leaves alone keeps "기본": font size, margins, status bar, 흑백 반전, TXT options.
        val untouched = d.copy(fontSizeSp = 24f, marginLeftDp = 12, footerLeft = StatusItem.NONE, invert = true, txtStripIndent = !d.txtStripIndent)
        assertTrue(StyleChoice.isDefault(untouched))
        // Any field a preset sets leaves it ("직접 설정"), and so does every preset.
        val tweaks = listOf(
            d.copy(fontId = "ridibatang"), d.copy(fontWeight = 500), d.copy(lineHeightPct = 170), d.copy(paragraphSpacingPct = 200),
            d.copy(indentPct = 100), d.copy(letterSpacingPm = 10), d.copy(align = Align.JUSTIFY), d.copy(lineBreak = LineBreakMode.CHAR),
            d.copy(pageTheme = PageTheme.MARU),
        )
        for (t in tweaks) assertFalse(t.toString(), StyleChoice.isDefault(t))
        for (p in StylePreset.entries) assertFalse(p.name, StyleChoice.isDefault(p.applyTo(d)))
        // A preset applied and the fields put back is "기본" again: the check covers exactly what a preset sets.
        for (p in StylePreset.entries) {
            val s = p.applyTo(d)
            assertTrue(p.name, StyleChoice.isDefault(s.copy(
                fontId = d.fontId, fontWeight = d.fontWeight, lineHeightPct = d.lineHeightPct, paragraphSpacingPct = d.paragraphSpacingPct,
                indentPct = d.indentPct, letterSpacingPm = d.letterSpacingPm, align = d.align, lineBreak = d.lineBreak, pageTheme = d.pageTheme,
            )))
        }
    }

    @Test
    fun defaultChoiceUndoesAnyPreset() {
        val d = ReaderSettings()
        for (p in StylePreset.entries) {
            // A preset with the user's own size, margins, 흑백 반전 and TXT options …
            val s = p.applyTo(d.copy(fontSizeSp = 23.5f, marginLeftDp = 30, invert = true, txtBlankLines = 2))
            val back = StyleChoice.applyDefault(s)
            // … goes back to "기본" (no preset marked) and keeps everything that is not part of a style.
            assertTrue(p.name, StyleChoice.isDefault(back))
            assertNull(p.name, StyleChoice.selected(back))
            assertEquals(23.5f, back.fontSizeSp, 0f)
            assertEquals(30, back.marginLeftDp)
            assertTrue(back.invert)
            assertEquals(2, back.txtBlankLines)
            assertEquals(d.copy(fontSizeSp = 23.5f, marginLeftDp = 30, invert = true, txtBlankLines = 2), back)
        }
    }

    @Test
    fun selectedPresetFollowsTypographyAndPageColours() {
        val d = ReaderSettings()
        for (p in StylePreset.entries) {
            val s = p.applyTo(d)
            assertEquals(p, StyleChoice.selected(s))
            // Exactly one preset is marked.
            assertEquals(1, StylePreset.entries.count { it.matches(s) })
            // Font size, margins, status bar and 흑백 반전 are not part of a style.
            assertEquals(p, StyleChoice.selected(s.copy(fontSizeSp = 24f, marginLeftDp = 30, footerLeft = StatusItem.NONE)))
            assertEquals(p, StyleChoice.selected(s.copy(invert = true)))
        }
        // A typography tweak or another 화면 색 leaves every preset ("직접 설정": nothing inverted).
        assertNull(StyleChoice.selected(StylePreset.MARU.applyTo(d).copy(lineHeightPct = 205)))
        assertNull(StyleChoice.selected(StylePreset.MARU.applyTo(d).copy(pageTheme = PageTheme.PAPER)))
        assertNull(StyleChoice.selected(StylePreset.RIDI.applyTo(d).copy(pageTheme = PageTheme.MARU)))
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
