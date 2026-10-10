package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The reader chrome's colours: one formula (the page's background mixed toward its text colour) on a phone's 흰 바탕 and
 * 흑백 반전 (the greys of the RIDI book app on 흰 바탕), the earlier chrome with MaruViewer's gold on 마루뷰어 and 검은 바탕,
 * the reader's ink on e-ink.
 */
class ChromePaletteTest {

    private val pages = listOf(PagePalette.PAPER, PagePalette.MARU, PagePalette.NIGHT, PagePalette.BLACK)

    /** The pages whose phone chrome is the RIDI formula; 마루뷰어 and 검은 바탕 keep their earlier chrome. */
    private val formula = listOf(PagePalette.PAPER, PagePalette.NIGHT)

    private fun all(): List<ChromePalette> =
        pages.flatMap { listOf(ChromePalette.of(it, false), ChromePalette.of(it, true)) }

    private fun name(k: ChromePalette) = Integer.toHexString(k.page) + if (k.eink) " e-ink" else " phone"

    private fun grey(c: Int) = c and 0xFF

    private fun mix(p: PagePalette, share: Float) = PagePalette.blend(p.background, p.text, share)

    @Test
    fun eightSharedSets() {
        for (p in pages) {
            for (eink in listOf(false, true)) assertSame(ChromePalette.of(p, eink), ChromePalette.of(p, eink))
            assertNotSame(ChromePalette.of(p, false), ChromePalette.of(p, true))
        }
        assertEquals(8, all().toSet().size)
        // Not probed yet: the e-ink set (no motion, nothing that ghosts) until the probe answers.
        for (p in pages) assertSame(ChromePalette.of(p, true), ChromePalette.of(p, null))
        assertSame(ChromePalette.of(PagePalette.PAPER, true), ChromePalette.DEFAULT)
    }

    @Test
    fun theHistoryRowAndTheBrightnessRowSitOnThePageColour() {
        // The row is filled with the page's own background in every look: phone and e-ink alike.
        for (p in pages) {
            for (eink in listOf(false, true, null)) assertEquals(p.background, ChromePalette.of(p, eink).page)
        }
        // ...not the bar's surface on a phone (the bars are a faint tone off it).
        for (p in pages) assertNotEquals(p.background, ChromePalette.of(p, false).surface)
    }

    @Test
    fun everyPhoneToneIsTheBackgroundMixedTowardTheText() {
        for (p in formula) {
            val k = ChromePalette.of(p, false)
            assertEquals(mix(p, 0.06f), k.surface)
            assertEquals(mix(p, 0.13f), k.topEdge)
            assertEquals(mix(p, 0.20f), k.bottomEdge)
            assertEquals(mix(p, 0.20f), k.track)
            assertEquals(mix(p, 0.43f), k.thumb)
            assertEquals(mix(p, 0.46f), k.text2)
            assertEquals(mix(p, 0.62f), k.text)
            assertEquals(k.text, k.hist)
        }
    }

    @Test
    fun paperGivesRidisGreys() {
        // Measured on RIDI's white page (1080 × 2340 S25): surface #F0F1F2, top-bar line #DDDEDF, bottom-bar line and
        // slider track #CCCDCE, thumb #909091, back arrow / secondary text #88898A, icons / labels / title #606061.
        val k = ChromePalette.of(PagePalette.PAPER, false)
        for ((have, ridi) in listOf(k.surface to 0xF0, k.topEdge to 0xDD, k.bottomEdge to 0xCC, k.track to 0xCC,
            k.thumb to 0x90, k.text2 to 0x88, k.text to 0x60)) {
            assertTrue("${Integer.toHexString(have)} vs ${Integer.toHexString(ridi)}", abs(grey(have) - ridi) <= 2)
        }
        assertEquals(0xFFF0F0F0.toInt(), k.surface)
        assertEquals(0xFF616161.toInt(), k.text)
    }

    @Test
    fun theInvertedPageGetsTheSameTreatment() {
        // 흑백 반전 (#000000 / #FFFFFF): the tones lie between the page and its text, in the order surface < edges <
        // thumb < secondary < primary, measured from the page.
        for (p in formula) {
            val k = ChromePalette.of(p, false)
            val d = { c: Int -> abs(grey(c) - grey(p.background)) }
            assertTrue(name(k), d(k.surface) < d(k.topEdge))
            assertTrue(name(k), d(k.topEdge) < d(k.bottomEdge))
            assertTrue(name(k), d(k.bottomEdge) <= d(k.track))
            assertTrue(name(k), d(k.track) < d(k.thumb))
            assertTrue(name(k), d(k.thumb) < d(k.text2))
            assertTrue(name(k), d(k.text2) < d(k.text))
            assertTrue(name(k), d(k.text) < abs(grey(p.text) - grey(p.background)))
        }
    }

    @Test
    fun greyAndBlackKeepTheirEarlierChrome() {
        // User (2026-10-10): "회색하고 검은색배경은 상태표시줄 원래색으로 … 탭 했을 때 나오던 것도 색 똑같이".
        val gold = PagePalette.MARU_GOLD
        val maru = ChromePalette.of(PagePalette.MARU, false)
        assertEquals(PagePalette.MARU.background, maru.page)
        assertEquals(0xFF3C3C3C.toInt(), maru.surface)
        assertEquals(0xFFDDDDDD.toInt(), maru.text)
        assertEquals(0xFFA8A8A8.toInt(), maru.text2)
        assertEquals(0xFFA8A8A8.toInt(), maru.hist)
        assertEquals(0xFF4E4E4E.toInt(), maru.divider)
        assertEquals(0xFF606060.toInt(), maru.track)
        assertEquals(0x80000000.toInt(), maru.shadow)
        assertEquals(0, maru.topEdge)
        assertEquals(0, maru.bottomEdge)
        val black = ChromePalette.of(PagePalette.BLACK, false)
        assertEquals(PagePalette.BLACK.background, black.page)
        assertEquals(0xFF1A1A1A.toInt(), black.surface)
        assertEquals(0xFFDDDDDD.toInt(), black.text)
        assertEquals(0xFF333333.toInt(), black.topEdge)
        assertEquals(0xFF333333.toInt(), black.bottomEdge)
        assertEquals(0xFF4A4A4A.toInt(), black.track)
        assertEquals(0, black.shadow)
        for (k in listOf(maru, black)) {
            // What is on is MaruViewer's gold, the status line's colour on these pages.
            assertEquals(gold, k.accent)
            assertEquals(gold, k.thumb)
            assertEquals(PagePalette.MARU.status, k.accent)
            assertEquals(0x4DFFD387, k.active)
            assertEquals(0x1AFFFFFF, k.pressed)
            assertTrue(k.motion)
            assertTrue(k.dark)
            assertFalse(k.eink)
        }
        // E-ink keeps the reader's ink on every page.
        for (p in listOf(PagePalette.MARU, PagePalette.BLACK)) assertEquals(ChromePalette.of(p, true).text, ChromePalette.of(p, true).accent)
    }

    @Test
    fun noThemeKeepsAnAccentColour() {
        // The formula's chrome and the e-ink chrome are grey: the gold is 마루뷰어's and 검은 바탕's alone.
        val gold = setOf(ChromePalette.of(PagePalette.MARU, false), ChromePalette.of(PagePalette.BLACK, false))
        for (k in all()) {
            if (k in gold) continue
            assertEquals(name(k), k.text, k.accent)
            for (c in listOf(k.surface, k.text, k.text2, k.accent, k.divider, k.rule, k.edge, k.topEdge, k.bottomEdge,
                k.track, k.thumb, k.hist)) {
                assertEquals(name(k), grey(c), c shr 8 and 0xFF)
                assertEquals(name(k), grey(c), c shr 16 and 0xFF)
                assertEquals(name(k), 0xFF, c ushr 24)
            }
        }
    }

    @Test
    fun glyphsReadOnTheirSurface() {
        for (k in all()) {
            val n = name(k)
            // Icons, labels and the title: RIDI's #606061 on #F0F1F2 is 5.5 : 1; the formula keeps every theme above 3.5.
            assertTrue("$n text", contrast(k.text, k.surface) >= 3.5)
            assertTrue("$n text on the page", contrast(k.hist, k.page) >= 3.5)
            // Secondary glyphs (back arrow, page total, chapter buttons) are lighter, but still a glyph, not a line.
            assertTrue("$n text2", contrast(k.text2, k.surface) >= 2.5)
            assertTrue("$n text2 below text", contrast(k.text2, k.surface) < contrast(k.text, k.surface))
        }
        // RIDI's own numbers, for reference: the formula on 흰 바탕 is as strong.
        val k = ChromePalette.of(PagePalette.PAPER, false)
        assertTrue(contrast(k.text, k.surface) >= 5.0)
        assertTrue(contrast(k.text2, k.surface) >= 2.9)
    }

    @Test
    fun lineAndSliderFloors() {
        for (p in formula) {
            val k = ChromePalette.of(p, false)
            val n = name(k)
            // Hairlines and the track stay quiet against the panel, the thumb stands out of the track.
            assertTrue("$n top edge", contrast(k.topEdge, k.surface) < 1.5)
            assertTrue("$n track", contrast(k.track, k.surface) < 2.5)
            assertTrue("$n thumb on the track", contrast(k.thumb, k.track) >= 1.4)
            // The chip and the seek preview float over the page text: their border (the track tone) must show on the
            // page, lighter than the text.
            assertTrue("$n box border", contrast(k.track, k.page) >= 1.4)
            assertTrue("$n box border below text", contrast(k.track, k.page) < contrast(k.text, k.page))
            // Each bar stays near its page, a light bar on a light page and a dark one on a dark page.
            assertTrue("$n surface near page", contrast(k.surface, k.page) <= 1.3)
            assertEquals(n, p.dark, luminance(k.surface) < 0.18)
        }
    }

    @Test
    fun phonesMoveAndPressButShowNoShadowOrCircle() {
        for (p in formula) {
            val k = ChromePalette.of(p, false)
            assertTrue(k.motion)
            assertFalse(k.eink)
            assertEquals(p.dark, k.dark)
            assertTrue("pressed", k.pressed != 0)
            // Pressed is the text colour at 10 %.
            assertEquals(0x1A, k.pressed ushr 24)
            assertEquals(p.text and 0xFFFFFF, k.pressed and 0xFFFFFF)
            // A state is shown by swapping icons, not by a circle behind them; the edges are hairlines, not shadows.
            assertEquals(0, k.active)
            assertEquals(0, k.shadow)
            assertEquals(k.divider, k.rule)
        }
    }

    @Test
    fun einkIsOneUpdatePerAction() {
        for (p in pages) {
            val k = ChromePalette.of(p, true)
            assertEquals("shadow", 0, k.shadow)
            assertEquals("pressed", 0, k.pressed)
            assertEquals("active", 0, k.active)
            assertFalse(k.motion)
            assertTrue(k.eink)
            assertEquals(p.dark, k.dark)
            // The surface is the page itself: a bar is told from the page by its solid 1 px line.
            assertEquals(p.background, k.surface)
            assertEquals(p.background, k.page)
            assertTrue(k.topEdge != 0 && k.bottomEdge != 0)
        }
    }

    @Test
    fun einkTonesAreOnThePanelsGreyLevels() {
        for (p in pages) {
            val k = ChromePalette.of(p, true)
            for (c in listOf(k.text, k.text2, k.accent, k.divider, k.rule, k.edge, k.topEdge, k.bottomEdge, k.track,
                k.thumb, k.hist)) {
                assertEquals("${name(k)} ${Integer.toHexString(c)}", 0, grey(c) % 17)
            }
        }
        // 흰 바탕: the reader's black-and-white chrome (black glyphs, thumb and bar lines; #555, #999, #CCC).
        val k = ChromePalette.of(PagePalette.PAPER, true)
        val black = 0xFF000000.toInt()
        for (c in listOf(k.text, k.accent, k.hist, k.thumb, k.rule, k.edge, k.topEdge, k.bottomEdge)) assertEquals(black, c)
        assertEquals(0xFF555555.toInt(), k.text2)
        assertEquals(0xFF999999.toInt(), k.track)
        assertEquals(0xFFCCCCCC.toInt(), k.divider)
    }

    @Test
    fun everyEinkElementIsAtLeastTwoPanelLevelsFromThePage() {
        for (p in pages) {
            val k = ChromePalette.of(p, true)
            val page = level(p.background)
            for ((label, c) in listOf("text" to k.text, "text2" to k.text2, "divider" to k.divider, "rule" to k.rule,
                "edge" to k.edge, "topEdge" to k.topEdge, "bottomEdge" to k.bottomEdge, "track" to k.track,
                "thumb" to k.thumb, "hist" to k.hist)) {
                assertTrue("${name(k)} $label ${Integer.toHexString(c)}", abs(level(c) - page) >= 2)
            }
            // The thumb and its progress are told from the track, the glyphs from the lines.
            assertTrue("${name(k)} thumb vs track", abs(level(k.thumb) - level(k.track)) >= 2)
            assertTrue("${name(k)} text vs track", abs(level(k.text) - level(k.track)) >= 2)
        }
    }

    @Test
    fun einkGlyphsStayLegibleAndOrdered() {
        for (p in pages) {
            val k = ChromePalette.of(p, true)
            val page = level(p.background)
            val d = { c: Int -> abs(level(c) - page) }
            assertTrue(name(k), d(k.divider) < d(k.track))
            assertTrue(name(k), d(k.track) < d(k.text2))
            assertTrue(name(k), d(k.text2) < d(k.text))
            // Glyphs, the thumb and the bars' lines are the page's full ink: nothing thin is left in a light grey.
            for (c in listOf(k.thumb, k.topEdge, k.bottomEdge, k.rule, k.edge)) assertEquals(name(k), d(k.text), d(c))
            assertTrue("${name(k)} text on the page", contrast(k.text, k.page) >= 7.0)
        }
    }

    @Test
    fun einkSnapsOnlyTheToneNotTheDirection() {
        // A light page gets darker elements, a dark one lighter.
        for (p in pages) {
            val k = ChromePalette.of(p, true)
            for (c in listOf(k.text, k.text2, k.thumb, k.track, k.topEdge)) {
                if (p.dark) assertTrue(name(k), grey(c) > grey(k.page)) else assertTrue(name(k), grey(c) < grey(k.page))
            }
        }
    }

    /** The panel level (0..15) of a grey: its value / 17, rounded. */
    private fun level(c: Int): Int = (grey(c) + 8) / 17

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    /** WCAG contrast ratio. */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
