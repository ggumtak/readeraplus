package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The reader chrome's colours per page theme and device class (user feedback 2026-10-05: bars in the page's theme). */
class ChromePaletteTest {

    private val pages = listOf(PagePalette.PAPER, PagePalette.MARU, PagePalette.NIGHT)
    private fun rgb(v: Int): Int = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v

    private fun all(): List<ChromePalette> =
        pages.flatMap { listOf(ChromePalette.of(it, false), ChromePalette.of(it, true)) }

    @Test
    fun sixSharedSets() {
        for (p in pages) {
            for (eink in listOf(false, true)) assertSame(ChromePalette.of(p, eink), ChromePalette.of(p, eink))
            assertNotSame(ChromePalette.of(p, false), ChromePalette.of(p, true))
        }
        assertEquals(6, all().toSet().size)
        // Not probed yet: the e-ink set (no motion, nothing that ghosts) until the probe answers.
        for (p in pages) assertSame(ChromePalette.of(p, true), ChromePalette.of(p, null))
        assertSame(ChromePalette.of(PagePalette.PAPER, true), ChromePalette.DEFAULT)
    }

    @Test
    fun theHistoryRowSitsOnThePageColour() {
        for (p in pages) {
            assertEquals(p.background, ChromePalette.of(p, false).page)
            assertEquals(p.background, ChromePalette.of(p, true).page)
        }
    }

    @Test
    fun theAccentIsThePagesOwnStatusColour() {
        // Black on 흰 바탕, MaruViewer's gold, white on 흑백 반전: no colour borrowed from another app.
        for (p in pages) {
            assertEquals(p.status, ChromePalette.of(p, false).accent)
            assertEquals(p.status, ChromePalette.of(p, true).accent)
        }
        assertEquals(0xFFF0D096.toInt(), ChromePalette.of(PagePalette.MARU, false).accent)
    }

    @Test
    fun paperOnEinkIsTheChromeOfBefore() {
        val k = ChromePalette.of(PagePalette.PAPER, true)
        assertEquals(rgb(255), k.page)
        assertEquals(rgb(255), k.surface)
        assertEquals(rgb(0), k.text)
        assertEquals(0xFF555555.toInt(), k.text2)     // Ink.GRAY
        assertEquals(rgb(0), k.accent)
        assertEquals(0xFFCCCCCC.toInt(), k.divider)   // Ink.LINE_LIGHT
        assertEquals(rgb(0), k.rule)                  // the black hairlines
        assertEquals(rgb(0), k.edge)
        assertEquals(0xFF999999.toInt(), k.track)     // Ink.DISABLED
        assertEquals(rgb(0), k.hist)
        assertEquals(0xFF555555.toInt(), k.histOff)
        assertFalse(k.dark)
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
            // A solid 1 px edge where a bar meets the page, the surface is the page itself.
            assertEquals(p.text, k.edge)
            assertEquals(p.background, k.surface)
            for (c in listOf(k.surface, k.text, k.text2, k.divider, k.rule, k.edge, k.track, k.hist, k.histOff)) {
                assertEquals("opaque", 0xFF, c ushr 24)
            }
        }
    }

    @Test
    fun darkEinkPagesUseTheGreysOfPaper() {
        // The old chrome's greys on the dark pages, the way the renderer maps its highlight greys (PagePalette.grey).
        val paper = ChromePalette.of(PagePalette.PAPER, true)
        for (p in listOf(PagePalette.MARU, PagePalette.NIGHT)) {
            val k = ChromePalette.of(p, true)
            for ((have, was) in listOf(k.text to paper.text, k.text2 to paper.text2, k.divider to paper.divider,
                k.rule to paper.rule, k.edge to paper.edge, k.track to paper.track, k.hist to paper.hist,
                k.histOff to paper.histOff)) {
                assertEquals(p.grey(was and 0xFF), have)
            }
            assertTrue(k.dark)
        }
        assertEquals(0xFFA4A4A4.toInt(), ChromePalette.of(PagePalette.MARU, true).text2)
        assertEquals(0xFF333333.toInt(), ChromePalette.of(PagePalette.NIGHT, true).divider)
    }

    @Test
    fun phonesMoveAndShadowExceptOnBlack() {
        for (p in pages) {
            val k = ChromePalette.of(p, false)
            assertTrue(k.motion)
            assertFalse(k.eink)
            assertTrue("pressed", k.pressed != 0)
            assertTrue("active", k.active != 0)
            assertEquals(p.dark, k.dark)
            // Lines inside a panel are low contrast on a phone.
            assertEquals(k.divider, k.rule)
        }
        assertTrue(ChromePalette.of(PagePalette.PAPER, false).shadow != 0)
        assertTrue(ChromePalette.of(PagePalette.MARU, false).shadow != 0)
        assertEquals(0, ChromePalette.of(PagePalette.PAPER, false).edge)
        // A shadow is invisible on black: 흑백 반전 gets a 1 px line instead.
        val n = ChromePalette.of(PagePalette.NIGHT, false)
        assertEquals(0, n.shadow)
        assertEquals(0xFF333333.toInt(), n.edge)
        for (k in all()) assertTrue("an edge needs a shadow or a line", k.shadow != 0 || k.edge != 0)
    }

    @Test
    fun aDarkPageGetsADarkBar() {
        // The user's screenshot: a pure white bar over the MaruViewer page. Each bar stays near its page.
        for (p in pages) {
            val k = ChromePalette.of(p, false)
            val name = "${Integer.toHexString(k.surface)} near ${Integer.toHexString(k.page)}"
            assertTrue(name, contrast(k.surface, k.page) <= 1.3)
            assertEquals(p.dark, luminance(k.surface) < 0.18)
        }
    }

    @Test
    fun contrastFloors() {
        for (k in all()) {
            val name = Integer.toHexString(k.page) + if (k.eink) " e-ink" else " phone"
            assertTrue("$name text", contrast(k.text, k.surface) >= 7.0)
            assertTrue("$name text2", contrast(k.text2, k.surface) >= 4.5)
            assertTrue("$name history row", contrast(k.hist, k.page) >= 4.5)
            assertTrue("$name accent", contrast(k.accent, k.surface) >= 3.0)
            // Lower emphasis than the page label's text, but readable.
            assertTrue("$name history below the label", contrast(k.hist, k.page) <= contrast(k.text, k.surface))
        }
        for (p in pages) {
            val k = ChromePalette.of(p, false)
            // The inactive track and the lines inside a panel are low contrast; the progress stands out of the track.
            assertTrue("track", contrast(k.track, k.surface) < 2.5)
            assertTrue("divider", contrast(k.divider, k.surface) < 1.5)
            assertTrue("progress", contrast(k.accent, k.track) >= 3.0)
            // "📌 588쪽" on the pinned page: dimmer than a link of the row, never buried in the page.
            val name = Integer.toHexString(k.page)
            assertTrue("$name histOff", contrast(k.histOff, k.page) >= 3.5)
            assertTrue("$name histOff below hist", contrast(k.histOff, k.page) < contrast(k.hist, k.page))
            // The chip and the seek preview float over the page text: their border (the track colour) must show on
            // the page, lighter than the text.
            assertTrue("$name box border", contrast(k.track, k.page) >= 1.6)
            assertTrue("$name box border below text", contrast(k.track, k.page) < contrast(k.text, k.page))
        }
    }

    @Test
    fun anActiveToggleIsNotAPressThatNeverReleased() {
        // Bookmark, pin and rotation lock on a phone: the active circle is set apart from the pressed one, and the
        // accent icon reads on it.
        for (p in pages) {
            val k = ChromePalette.of(p, false)
            val name = Integer.toHexString(k.page)
            val pressed = over(k.pressed, k.surface)
            val active = over(k.active, k.surface)
            assertTrue("$name active vs pressed", contrast(active, pressed) >= 1.4)
            assertTrue("$name icon on active", contrast(k.accent, active) >= 3.0)
        }
    }

    /** [top] (ARGB) drawn over the opaque [under]: the colour on screen. */
    private fun over(top: Int, under: Int): Int {
        val a = (top ushr 24) / 255.0
        fun ch(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val u = (under shr shift) and 0xFF
            return Math.round(t * a + u * (1 - a)).toInt()
        }
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

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
