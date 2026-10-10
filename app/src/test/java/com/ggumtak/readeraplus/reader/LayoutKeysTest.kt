package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.engine.LineBreakMode
import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.format.epub.EpubPlanCache
import com.ggumtak.readeraplus.format.txt.TxtDocuments
import com.ggumtak.readeraplus.settings.MaruSize
import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import com.ggumtak.readeraplus.settings.StatusBands
import com.ggumtak.readeraplus.settings.StatusItem
import com.ggumtak.readeraplus.settings.VerticalMargin
import com.ggumtak.readeraplus.engine.PageBreakMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutKeysTest {
    private val s = ReaderSettings()
    private val density = 2f

    @Test
    fun theBodyOnlyBottomReserveBalancesTheCometWithoutMovingStatusOrProgress() {
        val before = LayoutKeys.geometry(s, 720, 1440, density, emPx = 40f)
        val after = LayoutKeys.geometry(s, 720, 1440, density, emPx = 40f, eink = true)
        assertEquals(before.viewHeight, after.viewHeight) // status/progress still draw against the full page view
        assertEquals(1440, after.viewHeight)
        assertEquals(75, after.contentTop)
        assertEquals(before.contentTop, after.contentTop)
        assertEquals(before.contentWidth, after.contentWidth)
        assertEquals(before.contentHeight - 14, after.contentHeight)
        assertEquals(1365, after.contentTop + after.contentHeight)
        assertEquals(after.contentTop, 1440 - after.contentTop - after.contentHeight)
        assertEquals(16, LayoutKeys.linesIn(after.contentHeight, LayoutKeys.config(s, after).lineHeightEm * 40f))
    }

    @Test
    fun theEinkReserveIsFourteenPhysicalPixelsAtEveryDensityAndPhonesKeepTheirBox() {
        for (d in listOf(1f, 2f, 3f)) {
            val phone = LayoutKeys.geometry(s, 720, 1440, d)
            assertEquals(phone, LayoutKeys.geometry(s, 720, 1440, d, eink = false))
            val ink = LayoutKeys.geometry(s, 720, 1440, d, eink = true)
            assertEquals(phone.contentTop, ink.contentTop)
            assertEquals(phone.contentHeight - 14, ink.contentHeight)
            assertEquals(phone.viewHeight, ink.viewHeight)
        }
    }

    @Test
    fun theBodyCannotConsumeTheBottomReserveWhenBandsAreOffOrMarginsDoNotFit() {
        val bare = s.copy(marginTopDp = 0, marginBottomDp = 0, headerLeft = StatusItem.NONE,
            headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE, progressBar = false)
        val g = LayoutKeys.geometry(bare, 720, 1440, density, eink = true)
        assertEquals(1426, g.contentTop + g.contentHeight)
        for (height in listOf(30, 100, 1440)) {
            val huge = LayoutKeys.geometry(s.copy(marginTopDp = 9999, marginBottomDp = 9999),
                720, height, density, extraTop = height, eink = true)
            assertTrue(huge.contentHeight >= 1)
            assertTrue(huge.contentTop + huge.contentHeight <= height - 14)
        }
    }

    @Test
    fun theSmallerEinkBodyHasItsOwnTxtAndEpubPageCounts() {
        val phone = LayoutKeys.geometry(s, 720, 1440, density, emPx = 40f)
        val ink = LayoutKeys.geometry(s, 720, 1440, density, emPx = 40f, eink = true)
        for (format in listOf(BookFormat.TXT, BookFormat.EPUB)) {
            assertNotEquals(LayoutKeys.keyFor(s, format, "", phone, density, "font"),
                LayoutKeys.keyFor(s, format, "", ink, density, "font"))
        }
    }

    @Test
    fun geometryWithMarginsHeaderFooter() {
        val g = LayoutKeys.geometry(s, 720, 1440, density)
        // MaruViewer's 20 dp at the sides; the header's band (25 dp) at the top, the progress line's (18 dp) at the bottom,
        // and the two margins (15 + 10 dp) split evenly between them (2026-10-10: the same paper above and below the
        // text): the Comet's rows 75..1379, as tall as the 80..1384 the text box had before.
        assertEquals(40, g.contentLeft)
        assertEquals(75, g.contentTop)
        assertEquals(720 - 80, g.contentWidth)
        assertEquals(1440 - 136, g.contentHeight)
        assertEquals(1379, g.contentTop + g.contentHeight)
    }

    /** The body line the engine draws in [g]'s layout, px (1 em = [em]). */
    private fun pitchOf(t: ReaderSettings, g: PageGeometry, em: Float): Float = LayoutKeys.config(t, g).lineHeightEm * em

    @Test
    fun theLinesFillTheBoxBetweenTheMargins() {
        // Comet, default settings: box rows 75..1379 (1304 px). 1 em = 40 px, 200 %: 16 lines of 80 px fit and the 24 px
        // left over stretch them to 81.5 px, so the 16th line ends at the bottom margin.
        val em = 40f
        val g = LayoutKeys.geometry(s, 720, 1440, density, emPx = em)
        assertEquals(75, g.contentTop)
        assertEquals(1304, g.contentHeight)
        val pitch = pitchOf(s, g, em)
        assertEquals(1304f / 16, pitch, 0.01f)
        assertTrue(16 * pitch <= 1304f)
        assertEquals(16, LayoutKeys.linesIn(g.contentHeight, pitch))
        // paragraph spacing stretches alike, by less than a line's share
        val c = LayoutKeys.config(s, g)
        assertEquals(s.paragraphSpacingPct / 100f * pitch / 80f, c.paragraphSpacingEm, 1e-4f)
        assertTrue(g.lineScale >= 1f && g.lineScale < 1f + 1f / 16)
        // the side geometry is untouched; without em nothing stretches
        val plain = LayoutKeys.geometry(s, 720, 1440, density)
        assertEquals(plain.contentLeft, g.contentLeft)
        assertEquals(plain.contentWidth, g.contentWidth)
        assertEquals(s.lineHeightPct / 100f, LayoutKeys.config(s, plain).lineHeightEm, 0f)
    }

    @Test
    fun raisingBothVerticalMarginsPushesTheTopDownAndTheBottomUp() {
        // The user's ask (2026-10-10): at every step of 상하 여백 the text moves, the top line down and the last one up,
        // the line spacing never closer than the settings' and never more than a line's share wider.
        val em = 40f
        var prev: PageGeometry? = null
        var drops = 0
        for (ui in -10..64 step 2) {
            val t = s.copy(marginTopDp = VerticalMargin.topDp(ui), marginBottomDp = VerticalMargin.bottomDp(ui))
            val g = LayoutKeys.geometry(t, 720, 1440, density, emPx = em)
            val pitch = pitchOf(t, g, em)
            val n = LayoutKeys.linesIn(g.contentHeight, pitch)
            // (a hair under the box: n lines always fit, so the line may be a few thousandths of a px under 80)
            assertTrue("ui $ui: pitch $pitch", pitch >= 80f - 0.01f && pitch < 80f * (1f + 1f / n))
            val p = prev
            if (p != null) {
                val pp = pitchOf(t, p, em)
                val pn = LayoutKeys.linesIn(p.contentHeight, pp)
                assertTrue("ui $ui: top line moves down", g.contentTop > p.contentTop)
                if (n == pn) {
                    assertTrue("ui $ui: last line moves up", g.contentTop + n * pitch < p.contentTop + pn * pp)
                    assertTrue("ui $ui: lines closer", pitch < pp)
                } else {
                    drops++
                }
            }
            prev = g
        }
        assertTrue("the sweep drops lines", drops >= 2)
    }

    @Test
    fun atTheStepperEndsBothEdgesStillMove() {
        // Below -10 only the top margin changes, above +65 only the bottom one: split evenly, both edges of the text
        // still move inward (half a step each).
        val em = 40f
        fun g(ui: Int) = LayoutKeys.geometry(
            s.copy(marginTopDp = VerticalMargin.topDp(ui), marginBottomDp = VerticalMargin.bottomDp(ui)), 720, 1440, density, emPx = em,
        )
        for (ui in (VerticalMargin.UI_MIN until -10) + (66 until VerticalMargin.UI_MAX)) {
            val a = g(ui); val b = g(ui + 1)
            assertTrue("ui $ui", b.contentTop >= a.contentTop)
            assertTrue("ui $ui", b.contentTop + b.contentHeight <= a.contentTop + a.contentHeight)
            assertTrue("ui $ui", b.contentHeight < a.contentHeight)
        }
    }

    @Test
    fun atASmallLineSpacingTheFontsOwnHeightIsTheLine() {
        // 줄 간격 100 % with a font 1.4 em tall: the engine's line is 56 px, not 40: 23 of them fit in 1304 px (by 40 px
        // lines it would count 32), stretched to fill it.
        val em = 40f
        val t = s.copy(lineHeightPct = 100)
        val g = LayoutKeys.geometry(t, 720, 1440, density, emPx = em, naturalLinePx = 56f)
        val pitch = pitchOf(t, g, em)
        assertEquals(1304f / 23, pitch, 0.01f)
        assertEquals(23, LayoutKeys.linesIn(g.contentHeight, pitch))
        // a font shorter than the nominal line changes nothing
        assertEquals(pitchOf(s, LayoutKeys.geometry(s, 720, 1440, density, emPx = em), em),
            pitchOf(s, LayoutKeys.geometry(s, 720, 1440, density, emPx = em, naturalLinePx = 46f), em), 0f)
    }

    @Test
    fun fewLinesAreNotStretched() {
        // Three lines of a huge font would each grow by up to a third: they keep the settings' line.
        val big = s.copy(fontSizeSp = 200f)
        val g = LayoutKeys.geometry(big, 720, 1440, density, emPx = 400f)
        assertEquals(0f, g.fillLineEm, 0f)
        assertEquals(1f, g.lineScale, 0f)
        assertEquals(big.lineHeightPct / 100f, LayoutKeys.config(big, g).lineHeightEm, 0f)
        assertEquals(0, LayoutKeys.linesIn(500, 0f))
        assertEquals(1, LayoutKeys.linesIn(50, 80f))
    }

    /** The geometry before the bands (4efdf0b): margins from the screen's edges (below a cutout band), no status term. */
    private fun edgeGeometry(t: ReaderSettings, viewW: Int, viewH: Int, d: Float, extraTop: Int = 0): IntArray {
        fun px(dp: Int): Int = Math.round((if (t.pageMargins) dp else LayoutKeys.TINY_MARGIN_DP) * d)
        val top = extraTop + px(t.marginTopDp)
        return intArrayOf(px(t.marginLeftDp), top, viewW - px(t.marginLeftDp) - px(t.marginRightDp), viewH - px(t.marginBottomDp) - top)
    }

    private fun box(g: PageGeometry) = intArrayOf(g.contentLeft, g.contentTop, g.contentWidth, g.contentHeight)

    @Test
    fun theBodySitsMidwayBetweenTheBandsOnBothDevices() {
        // The user (2026-10-05): "코멧에서 본문 지금 자리 그대로", "S25 전체 화면도 지금 자리 유지". 40/40 saved from the
        // edge with MaruViewer's header, no footer items and the progress line read as the new defaults
        // (VerticalMargin.fromEdge, the 13 sp bands); since 2026-10-06 the bottom comes 12 dp closer (shiftBottom, once).
        val old = s.copy(marginTopDp = 40, marginBottomDp = 40)
        val now = VerticalMargin.fromEdge(old).let { it.copy(marginBottomDp = VerticalMargin.shiftBottom(it.marginBottomDp)) }
        assertEquals(s, now)
        val edge = old.copy(marginBottomDp = 40 - VerticalMargin.BOTTOM_SHIFT_DP)
        // Their devices since the bands: 18/22 at 11 sp. MaruViewer's 13 sp comes once (MaruSize): the same defaults.
        val eleven = s.copy(statusFontSizeSp = MaruSize.OLD_SP, marginTopDp = 18)
        assertEquals(s, MaruSize.keepBox(eleven, MaruSize.applyTo(eleven)))
        // Since 2026-10-10 (user: "윗 여백은 개넓은 거에 비해 아랫 여백은 별로 안 넓어"): the box keeps its height, the
        // two margins split evenly between the bands. Comet 720×1440 @2: rows 75..1379 (was 80..1384).
        val comet = LayoutKeys.geometry(now, 720, 1440, 2f)
        assertEquals(listOf(75, 1379), listOf(comet.contentTop, comet.contentTop + comet.contentHeight))
        assertEquals(edgeGeometry(edge, 720, 1440, 2f)[3], comet.contentHeight)
        // S25 1080×2340 @3, fullscreen: the 87 px camera band holds the header (its 75 px band is not stacked below it),
        // then half of the 25 dp margins: rows 124..2248 (was 132..2256).
        val full = LayoutKeys.geometry(now, 1080, 2340, 3f, extraTop = 87)
        assertEquals(listOf(124, 2248), listOf(full.contentTop, full.contentTop + full.contentHeight))
        assertEquals(87, full.cutoutTop)
        // A cutout band shorter than the header's: the header's band decides (no overlap).
        assertEquals(75 + 37, LayoutKeys.geometry(now, 1080, 2340, 3f, extraTop = 40).contentTop)
        // S25 with the system bars: the page view starts below the 110 px status bar and ends above the navigation bar
        // (whatever its inset): the box is 112 px inside the view at the top, 92 px at the bottom.
        for (bottomInset in listOf(0, 48, 63, 144)) {
            val viewH = 2340 - 110 - bottomInset
            val bars = LayoutKeys.geometry(now, 1080, viewH, 3f)
            assertEquals(112, bars.contentTop)
            assertEquals(viewH - 92, bars.contentTop + bars.contentHeight)
            assertEquals(edgeGeometry(edge, 1080, viewH, 3f)[3], bars.contentHeight)
        }
        // On any density: as tall as before (to a pixel of rounding), the paper above and below within a pixel.
        for (d in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)) {
            val g = LayoutKeys.geometry(now, 1000, 2000, d)
            assertTrue("density $d", Math.abs(edgeGeometry(edge, 1000, 2000, d)[3] - g.contentHeight) <= 1)
            val above = g.contentTop - Math.round(StatusBands.headerDp(now) * d)
            val below = 2000 - Math.round(StatusBands.footerDp(now) * d) - (g.contentTop + g.contentHeight)
            assertTrue("density $d: $above / $below", Math.abs(above - below) <= 1)
        }
    }

    @Test
    fun marginsCountFromTheBands() {
        // A margin of 0 puts the text right under the header's band (Comet: 25 dp = 50 px) and right above the footer's.
        val zero = s.copy(marginTopDp = 0, marginBottomDp = 0)
        val g = LayoutKeys.geometry(zero, 720, 1440, 2f)
        assertEquals(50, g.contentTop)
        assertEquals(1440 - 36, g.contentTop + g.contentHeight)
        // S25 fullscreen: the header sits inside the 87 px camera band: the text starts right under it.
        assertEquals(87, LayoutKeys.geometry(zero, 1080, 2340, 3f, extraTop = 87).contentTop)
        // Text box = reserves + margins, for any margin: one step of 2 dp is 4 px on the Comet.
        for (m in 0..80 step 2) {
            val t = LayoutKeys.geometry(s.copy(marginTopDp = m, marginBottomDp = m), 720, 1440, 2f)
            assertEquals(50 + 2 * m, t.contentTop)
            assertEquals(1440 - 36 - 2 * m, t.contentTop + t.contentHeight)
        }
        // Without bands the margins count from the edges again (half of 15 + 10 dp each side).
        val bare = s.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE, progressBar = false)
        val b = LayoutKeys.geometry(bare, 720, 1440, 2f)
        assertEquals(listOf(25, 1440 - 25), listOf(b.contentTop, b.contentTop + b.contentHeight))
        // Footer items above the line: a 39 dp band (78 px) under half of the two margins.
        val f = LayoutKeys.geometry(s.withSlot(1, 1, StatusItem.PAGE), 720, 1440, 2f)
        assertEquals(1440 - 78 - 25, f.contentTop + f.contentHeight)
        // An unbalanced pair (old migrations moved only the bottom one) shows balanced: the same paper above and below.
        val lop = LayoutKeys.geometry(s.copy(marginTopDp = 40, marginBottomDp = 0), 720, 1440, 2f)
        assertEquals(lop.contentTop - 50, 1440 - 36 - (lop.contentTop + lop.contentHeight))
        // "페이지 여백" off: the tiny margins, still clear of the bands.
        val off = LayoutKeys.geometry(s.copy(pageMargins = false), 720, 1440, 2f)
        assertEquals(listOf(50 + 8, 1440 - 36 - 8), listOf(off.contentTop, off.contentTop + off.contentHeight))
    }

    @Test
    fun sideMarginsAreTheSameShareOfTheWidthOnBothDevices() {
        // dp, not px: 20 dp is 60 of 1080 px on the S25 (density 3) and 40 of 720 px on the Comet (density 2).
        val s25 = LayoutKeys.geometry(s, 1080, 2340, 3f)
        val comet = LayoutKeys.geometry(s, 720, 1440, 2f)
        assertEquals(60, s25.contentLeft)
        assertEquals(40, comet.contentLeft)
        assertEquals(s25.contentLeft / 1080f, comet.contentLeft / 720f, 1e-6f)
        assertEquals(1080 - s25.contentWidth, 2 * s25.contentLeft)
    }

    @Test
    fun aCutoutBandAtTheTopKeepsTheTextBoxWhereItWas() {
        // Fullscreen on the S25: the page view starts at the screen's top edge; the camera band is left out like a system
        // bar, and the header, drawn inside it, shares it (2026-10-06): the text starts half of the two margins under the
        // taller of the band and the header's band. Sides and bottom are those of the view laid out below the band.
        val noHeader = s.copy(headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE, headerRight = StatusItem.NONE)
        for (band in listOf(0, 1, 87, 120)) for (t in listOf(s, s.copy(pageMargins = false), s.copy(marginTopDp = 0), noHeader)) {
            val below = LayoutKeys.geometry(t, 1080, 2340 - band, 3f)
            val into = LayoutKeys.geometry(t, 1080, 2340, 3f, extraTop = band)
            val margins = Math.round((if (t.pageMargins) t.marginTopDp + t.marginBottomDp else 2 * LayoutKeys.TINY_MARGIN_DP) * 3f)
            val top = if (band == 0) below.contentTop else maxOf(band, Math.round(StatusBands.headerDp(t) * 3f)) + margins / 2
            assertEquals(below.contentLeft, into.contentLeft)
            assertEquals(top, into.contentTop)
            assertEquals(below.contentWidth, into.contentWidth)
            assertEquals(2340, into.viewHeight)
            // The band is kept with the geometry (thumbnails leave it out: the same page as `below`).
            assertEquals(band, into.cutoutTop)
            assertEquals(0, below.cutoutTop)
            assertEquals(below.viewHeight, into.viewHeight - into.cutoutTop)
            // The bottom margin is the same, so the footer and the progress lane stay where they were.
            assertEquals(below.viewHeight - below.contentTop - below.contentHeight, into.viewHeight - into.contentTop - into.contentHeight)
        }
        // No cutout (the Comet): nothing changes.
        assertEquals(LayoutKeys.geometry(s, 720, 1440, 2f), LayoutKeys.geometry(s, 720, 1440, 2f, extraTop = 0))
        // A box too small for the margins is centred below the band.
        val tight = s.copy(marginTopDp = 900, marginBottomDp = 900)
        val g = LayoutKeys.geometry(tight, 1080, 2340, 3f, extraTop = 87)
        assertTrue(g.contentTop >= 87)
        assertEquals(LayoutKeys.geometry(tight, 1080, 2340 - 87, 3f).contentTop + 87, g.contentTop)
    }

    @Test
    fun geometryWithoutMarginsAndBars() {
        val t = s.copy(pageMargins = false, headerLeft = StatusItem.NONE, headerCenter = StatusItem.NONE,
            headerRight = StatusItem.NONE, progressBar = false)
        val g = LayoutKeys.geometry(t, 720, 1440, density)
        assertEquals(8, g.contentLeft)
        assertEquals(8, g.contentTop)
        assertEquals(704, g.contentWidth)
        assertEquals(1424, g.contentHeight)
        // The default bands stay reserved with the margins off.
        val bands = LayoutKeys.geometry(s.copy(pageMargins = false), 720, 1440, density)
        assertEquals(50 + 8, bands.contentTop)
        assertEquals(1440 - 36 - 8, bands.contentTop + bands.contentHeight)
    }

    @Test
    fun geometryNeverCollapses() {
        val t = s.copy(marginLeftDp = 400, marginRightDp = 400, marginTopDp = 900, marginBottomDp = 900)
        val g = LayoutKeys.geometry(t, 720, 1440, density)
        assertTrue(g.contentWidth >= 16)
        assertTrue(g.contentHeight >= 16)
        assertTrue(g.contentLeft + g.contentWidth <= 720)
        assertTrue(g.contentTop + g.contentHeight <= 1440)
    }

    @Test
    fun configFromSettings() {
        val t = s.copy(lineHeightPct = 185, paragraphSpacingPct = 120, indentPct = 150, align = Align.LEFT, lineBreak = LineBreakMode.CHAR)
        val c = LayoutKeys.config(t, LayoutKeys.geometry(t, 720, 1440, density))
        assertEquals(1.85f, c.lineHeightEm, 1e-6f)
        assertEquals(1.2f, c.paragraphSpacingEm, 1e-6f)
        assertEquals(1.5f, c.indentEm, 1e-6f)
        assertEquals(Align.LEFT, c.align)
        assertEquals(LineBreakMode.CHAR, c.lineBreak)
        assertEquals(640, c.width)
    }

    @Test
    fun layoutChangeDetection() {
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(invert = true)))
        // 화면 색 is colours only, like 흑백 반전: a repaint, never a re-layout (the first character stays).
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(pageTheme = PageTheme.MARU)))
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(pageTheme = PageTheme.MARU), BookFormat.TXT))
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(pageTheme = PageTheme.MARU), BookFormat.EPUB))
        // A footer band that comes moves the text box's bottom (2026-10-05: the margins count from the bands); one footer
        // item for another (R2 items too) is a repaint.
        val footer = s.copy(footerLeft = StatusItem.CLOCK, footerCenter = StatusItem.BATTERY, footerRight = StatusItem.CHAPTER_PAGES_LEFT)
        assertTrue(LayoutKeys.layoutChanged(s, footer))
        assertFalse(LayoutKeys.layoutChanged(footer, footer.copy(footerLeft = StatusItem.EPISODE, footerCenter = StatusItem.TIME_LEFT_BOOK)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(fontSizeSp = 21f)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(paragraphSpacingPct = 60)))
        // The header keeps its band while any slot has an item; the progress line has a band of its own.
        assertFalse(LayoutKeys.layoutChanged(s, s.copy(headerCenter = StatusItem.NONE)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(progressBar = false)))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(progressBar = false), BookFormat.TXT))
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(progressBar = false), BookFormat.EPUB))
        // (the default font is 나눔명조 since the Maru-style defaults: switch to another one)
        assertTrue(LayoutKeys.layoutChanged(s, s.copy(fontId = if (s.fontId == "ridibatang") "nanummyeongjo" else "ridibatang")))
    }

    @Test
    fun parseChangeDetection() {
        assertFalse(LayoutKeys.parseChanged(s, s.copy(fontSizeSp = 30f), ""))
        assertTrue(LayoutKeys.parseChanged(s, s.copy(txtBlankLines = 3), ""))
        assertTrue(LayoutKeys.parseChanged(s, s.copy(txtReplaceRules = "광고 => "), "MS949"))
    }

    @Test
    fun keyIsStableAndSensitive() {
        val g = LayoutKeys.geometry(s, 720, 1440, density)
        fun key(t: ReaderSettings = s, enc: String = "", gg: PageGeometry = g, font: String = "BUNDLED:fonts/RIDIBatang.otf", ver: Int = LayoutKeys.ALGO_VERSION) =
            LayoutKeys.key(t, t.parseOptions(enc), gg, density, font, ver)
        val base = key()
        assertEquals(24, base.length)
        assertEquals(base, key())
        assertEquals(base, key(s.copy(invert = true)))
        assertEquals(base, key(s.copy(pageTheme = PageTheme.MARU)))
        assertNotEquals(base, key(s.copy(fontSizeSp = 20.5f)))
        assertNotEquals(base, key(s.copy(letterSpacingPm = 10)))
        assertNotEquals(base, key(enc = "MS949"))
        assertNotEquals(base, key(s.copy(txtDetectChapters = false)))
        assertNotEquals(base, key(gg = LayoutKeys.geometry(s, 1440, 720, density)))
        assertNotEquals(base, key(font = "USER:/x.ttf:100:5"))
        assertNotEquals(base, key(ver = LayoutKeys.ALGO_VERSION + 1))
    }

    @Test
    fun keyCarriesTheLayoutAlgorithmVersionNotTheAppVersion() {
        // A2: an app update that leaves the typesetter alone keeps every cached count; ALGO_VERSION is the default.
        val g = LayoutKeys.geometry(s, 720, 1440, density)
        val font = "BUNDLED:fonts/NanumMyeongjo.ttf"
        val k = LayoutKeys.keyFor(s, BookFormat.TXT, "", g, density, font)
        assertEquals(LayoutKeys.keyFor(s, BookFormat.TXT, "", g, density, font, LayoutKeys.ALGO_VERSION), k)
        assertNotEquals(LayoutKeys.keyFor(s, BookFormat.TXT, "", g, density, font, LayoutKeys.ALGO_VERSION + 1), k)
        assertEquals(LayoutKeys.key(s, s.parseOptions(""), g, density, font), LayoutKeys.key(s, s.parseOptions(""), g, density, font, LayoutKeys.ALGO_VERSION))
        // 2: the hinted body paints (CrispText, 2026-10-05) measure whole-px advances in every font, so no count made
        // with the linear ones may be reused.
        assertTrue(LayoutKeys.ALGO_VERSION >= 2)
        assertNotEquals(LayoutKeys.keyFor(s, BookFormat.TXT, "", g, density, font, 1), k)
        assertTrue(LayoutKeys.VERSION >= 3)
        // LayoutGoldenTest compares its 64-bit digest with this.
        assertTrue(LayoutKeys.GOLDEN_HASH.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun keyCarriesTheFormatsParseVersion() {
        // A2: without the app's version code, a parser update that changes section text but keeps the section count
        // must still invalidate the counts (PageCounts.setKnown checks only the length).
        val g = LayoutKeys.geometry(s, 720, 1440, density)
        val font = "BUNDLED:fonts/NanumMyeongjo.ttf"
        assertEquals(TxtDocuments.PARSE_VERSION, LayoutKeys.parseVersionOf(BookFormat.TXT))
        assertEquals(EpubPlanCache.VERSION, LayoutKeys.parseVersionOf(BookFormat.EPUB))
        for (f in BookFormat.values()) {
            val k = LayoutKeys.keyFor(s, f, "", g, density, font)
            assertEquals(LayoutKeys.keyFor(s, f, "", g, density, font, LayoutKeys.ALGO_VERSION, LayoutKeys.parseVersionOf(f)), k)
            assertNotEquals(LayoutKeys.keyFor(s, f, "", g, density, font, LayoutKeys.ALGO_VERSION, LayoutKeys.parseVersionOf(f) + 1), k)
        }
    }

    @Test
    fun epubChapterDetectionOptionsChangeTheKeyAndReparse() {
        // An EPUB with a poor TOC gets detected chapters (page breaks, heading look, split points): these options
        // re-parse it, and its cached counts follow them. The other TXT options still do not touch an EPUB.
        val g = LayoutKeys.geometry(s, 720, 1440, density)
        val font = "BUNDLED:fonts/NanumMyeongjo.ttf"
        val k = LayoutKeys.keyFor(s, BookFormat.EPUB, "", g, density, font)
        for (changed in listOf(s.copy(txtDetectChapters = false), s.copy(txtChapterRegex = "^-\\d+-$"), s.copy(txtEmphasizeHeadings = false))) {
            assertNotEquals(k, LayoutKeys.keyFor(changed, BookFormat.EPUB, "", g, density, font))
            assertTrue(LayoutKeys.parseChanged(s, changed, BookFormat.EPUB, ""))
            assertFalse("no new layout of the old parse", LayoutKeys.layoutChanged(s, changed, BookFormat.EPUB))
        }
        val o = LayoutKeys.parseOptionsFor(s.copy(txtChapterRegex = "x", txtDetectChapters = false), BookFormat.EPUB, "")
        assertEquals("x", o.txtChapterRegex)
        assertFalse(o.txtDetectChapters)
        val other = s.copy(txtBlankLines = 2, txtJoinWrappedLines = 0, txtReplaceRules = "a => b", txtStripIndent = false)
        assertFalse(LayoutKeys.parseChanged(s, other, BookFormat.EPUB, ""))
        assertEquals(k, LayoutKeys.keyFor(other, BookFormat.EPUB, "", g, density, font))
    }

    @Test
    fun effectiveTxtOptionsOfOneBookChangeOnlyThatBooksKey() {
        // T1-9: the session keys its counts with the book's effective settings (global + its own TXT options).
        val g = LayoutKeys.geometry(s, 720, 1440, density)
        val font = "BUNDLED:fonts/NanumMyeongjo.ttf"
        val own = s.copy(txtBlankLines = 2, txtReplaceRules = "광고 => ")
        assertNotEquals(LayoutKeys.keyFor(s, BookFormat.TXT, "", g, density, font), LayoutKeys.keyFor(own, BookFormat.TXT, "", g, density, font))
        assertEquals(LayoutKeys.keyFor(s, BookFormat.EPUB, "", g, density, font), LayoutKeys.keyFor(own, BookFormat.EPUB, "", g, density, font))
    }

    @Test fun slotsMoveTheBoxOnlyThroughTheirBands() {
        // One item for another (none ↔ none, item ↔ item): the same box, no relayout, the same cached counts.
        val g=LayoutKeys.geometry(s,720,1440,density)
        val k=LayoutKeys.keyFor(s,BookFormat.TXT,"",g,density,"font")
        for (band in 0..1) for (pos in 0..2) for (item in StatusItem.entries) {
            val changed=s.withSlot(band,pos,item)
            val sameBand=(band==0 && changed.hasHeader) || (band==1 && item==StatusItem.NONE)
            val cg=LayoutKeys.geometry(changed,720,1440,density)
            assertEquals("$band/$pos=$item",sameBand,g==cg)
            assertEquals(!sameBand,LayoutKeys.layoutChanged(s,changed))
            assertEquals(!sameBand,LayoutKeys.layoutChanged(s,changed,BookFormat.EPUB))
            assertEquals(sameBand,k==LayoutKeys.keyFor(changed,BookFormat.TXT,"",cg,density,"font"))
        }
        // none ↔ item moves the box by the band: the header's (all three slots none), the footer's (one item).
        val noHeader=s.copy(headerLeft=StatusItem.NONE,headerCenter=StatusItem.NONE,headerRight=StatusItem.NONE)
        assertTrue(LayoutKeys.layoutChanged(s,noHeader))
        assertEquals(25,LayoutKeys.geometry(noHeader,720,1440,density).contentTop)
        assertFalse(LayoutKeys.layoutChanged(noHeader,noHeader.copy(statusFontSizeSp=16f)))
        // The status size moves a band with text (whole dp: 13 → 13.5 sp is 19 → 20 dp), not the progress line alone.
        assertTrue(LayoutKeys.layoutChanged(s,s.copy(statusFontSizeSp=13.5f)))
        assertEquals((4+20+2)*2+25,LayoutKeys.geometry(s.copy(statusFontSizeSp=13.5f),720,1440,density).contentTop)
        assertEquals(LayoutKeys.geometry(noHeader,720,1440,density),LayoutKeys.geometry(noHeader.copy(statusFontSizeSp=14f),720,1440,density))
        // A key is per box: a band change never reuses another box's counts; the box alone decides (the slots don't).
        assertNotEquals(k,LayoutKeys.keyFor(noHeader,BookFormat.TXT,"",LayoutKeys.geometry(noHeader,720,1440,density),density,"font"))
        assertTrue(LayoutKeys.bandsChanged(s,s.copy(progressBar=false)))
        assertFalse(LayoutKeys.bandsChanged(s,s.copy(headerLeft=StatusItem.CLOCK,headerRight=StatusItem.PERCENT)))
    }
    @Test fun pageBreakChangesConfigAndKey() {
        val g=LayoutKeys.geometry(s,720,1440,density);val p=s.copy(pageBreak=PageBreakMode.PARAGRAPH)
        assertTrue(LayoutKeys.layoutChanged(s,p))
        assertEquals(PageBreakMode.PARAGRAPH,LayoutKeys.config(p,g).pageBreak)
        assertNotEquals(LayoutKeys.keyFor(s,BookFormat.TXT,"",g,density,"font"),LayoutKeys.keyFor(p,BookFormat.TXT,"",g,density,"font"))
    }


}
