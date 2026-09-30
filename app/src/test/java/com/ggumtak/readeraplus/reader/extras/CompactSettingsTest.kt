package com.ggumtak.readeraplus.reader.extras

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

    // ------------------------------------------------------------------ popup size maths

    @Test
    fun popupWidthIs86PercentCappedAt330dp() {
        // 720 px wide at 2.0 (360 dp): 86% = 619 px (309 dp) < 330 dp.
        assertEquals(619, PopupGeometry.width(720, 2f))
        // 1600 px at 2.0 (800 dp): capped at 330 dp.
        assertEquals(660, PopupGeometry.width(1600, 2f))
        // 1080 px at 3.0 (360 dp).
        assertEquals(928, PopupGeometry.width(1080, 3f))
        assertTrue(PopupGeometry.width(100, 2f) <= 100)
        assertEquals(1, PopupGeometry.width(0, 2f))
    }

    @Test
    fun settingsPopupAtMost55PercentTall() {
        // 360×720 dp at 2.0 under a 56 dp top bar: 55% of 1440 = 792 px (396 dp).
        val p = PopupGeometry.settings(1440, 112, 2f)
        assertEquals(112, p.top)
        assertEquals(792, p.height)
        // Little room under the anchor (landscape): at least 160 dp, moved up to stay on screen.
        val q = PopupGeometry.settings(600, 500, 2f)
        assertEquals(320, q.height)
        assertEquals(600 - 16 - 320, q.top)
        // Tiny window: never taller than 55%.
        val r = PopupGeometry.settings(400, 380, 2f)
        assertTrue(r.height <= 220)
        assertTrue(r.top >= 0 && r.top + r.height <= 400)
    }

    @Test
    fun mainSectionFitsTheCometWithoutScrollingAndLeavesHalfThePage() {
        // Comet: 720×1440 px at 2.0 (360×720 dp); the bars are hidden while the popup is open (immersive: inset 0).
        val density = 2f
        val screenH = 1440
        val place = PopupGeometry.settings(screenH, 0, density)
        assertEquals(0, place.top)
        val main = Math.round(PopupGeometry.MAIN_ROWS * Compact.ROW_DP * density)
        // No scrolling needed for 스타일 … 더보기 (the old 40 dp rows made 400 dp > the 396 dp cap).
        assertTrue("main section $main px > cap ${place.height} px", main <= place.height)
        // The popup (main section + 1 px border each side) covers at most the top half: the page below is the preview.
        assertTrue("popup ${main + 2} px", place.top + main + 2 <= screenH / 2 + 2)
        // Stepper buttons make rows no taller than the others.
        assertTrue(Compact.STEP_DP <= Compact.ROW_DP)
    }

    @Test
    fun dropdownPlacement() {
        // Fits under the row.
        val below = PopupGeometry.dropdown(1440, 300, 380, 400, 2f)
        assertEquals(380, below.top)
        assertEquals(400, below.height)
        // No room below: above the row.
        val above = PopupGeometry.dropdown(1440, 1000, 1080, 500, 2f)
        assertEquals(500, above.top)
        assertEquals(500, above.height)
        // Taller than 55%: capped (scrolls); no room either side → as low as fits.
        val tall = PopupGeometry.dropdown(1440, 600, 680, 2000, 2f)
        assertEquals(792, tall.height)
        assertEquals(1440 - 16 - 792, tall.top)
        // Right-aligned to the anchor, kept on screen.
        assertEquals(100, PopupGeometry.dropdownLeft(720, 700, 600))
        assertEquals(0, PopupGeometry.dropdownLeft(720, 500, 600))
        assertEquals(120, PopupGeometry.dropdownLeft(720, 800, 600))
        assertEquals(0, PopupGeometry.dropdownLeft(500, 500, 600))
    }

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
            assertEquals(p, StyleChoice.selected(s.copy(fontSizeSp = 24f, marginLeftDp = 30, showFooter = false)))
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
