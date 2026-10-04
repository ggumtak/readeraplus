package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.format.BookFormat
import com.ggumtak.readeraplus.settings.PageTheme
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the reader-core review fixes (pure logic only). */
class ReaderReviewFixesTest {
    private val s = ReaderSettings()
    private val density = 2f
    private val g = LayoutKeys.geometry(s, 720, 1440, density)
    private val font = "BUNDLED:fonts/RIDIBatang.otf"

    // ---------------------------------------------------------------- per-format parse / layout identity

    @Test
    fun txtOptionNeverReparsesOrRelayoutsAnEpub() {
        val t = s.copy(txtBlankLines = 3, txtStripIndent = false, txtReplaceRules = "광고 => ", txtDetectChapters = false)
        assertFalse(LayoutKeys.parseChanged(s, t, BookFormat.EPUB, ""))
        assertFalse(LayoutKeys.layoutChanged(s, t, BookFormat.EPUB))
        assertTrue(LayoutKeys.parseChanged(s, t, BookFormat.TXT, ""))
        assertTrue(LayoutKeys.layoutChanged(s, t, BookFormat.TXT))
        // The format-less overloads keep their old meaning for other callers.
        assertTrue(LayoutKeys.parseChanged(s, t, ""))
    }

    @Test
    fun publisherStylesNeverReparsesOrRelayoutsATxt() {
        val t = s.copy(epubPublisherStyles = !s.epubPublisherStyles)
        assertFalse(LayoutKeys.parseChanged(s, t, BookFormat.TXT, "MS949"))
        assertFalse(LayoutKeys.layoutChanged(s, t, BookFormat.TXT))
        assertTrue(LayoutKeys.parseChanged(s, t, BookFormat.EPUB, ""))
        assertTrue(LayoutKeys.layoutChanged(s, t, BookFormat.EPUB))
    }

    @Test
    fun encodingOnlyMattersForTxt() {
        assertEquals(LayoutKeys.parseOptionsFor(s, BookFormat.EPUB, "MS949"), LayoutKeys.parseOptionsFor(s, BookFormat.EPUB, ""))
        assertNotEquals(LayoutKeys.parseOptionsFor(s, BookFormat.TXT, "MS949"), LayoutKeys.parseOptionsFor(s, BookFormat.TXT, ""))
    }

    @Test
    fun pageCountKeyIgnoresTheOtherFormatsOptions() {
        fun key(t: ReaderSettings, f: BookFormat, enc: String = "") = LayoutKeys.keyFor(t, f, enc, g, density, font, 1)
        val txtChange = s.copy(txtBlankLines = 3, txtReplaceRules = "a => b", txtEmphasizeHeadings = false)
        val pubChange = s.copy(epubPublisherStyles = !s.epubPublisherStyles)
        // A TXT option keeps every EPUB's counts; publisher styles keep every TXT's counts.
        assertEquals(key(s, BookFormat.EPUB), key(txtChange, BookFormat.EPUB))
        assertEquals(key(s, BookFormat.TXT), key(pubChange, BookFormat.TXT))
        // ... but still invalidate their own format.
        assertNotEquals(key(s, BookFormat.TXT), key(txtChange, BookFormat.TXT))
        assertNotEquals(key(s, BookFormat.EPUB), key(pubChange, BookFormat.EPUB))
        assertNotEquals(key(s, BookFormat.TXT), key(s, BookFormat.TXT, "MS949"))
        // Colours / footer items never count.
        assertEquals(key(s, BookFormat.TXT), key(s.copy(invert = true), BookFormat.TXT))
        assertEquals(key(s, BookFormat.EPUB), key(s.copy(pageTheme = PageTheme.MARU), BookFormat.EPUB))
        assertTrue(LayoutKeys.VERSION >= 2)
    }

    // ---------------------------------------------------------------- font weight layout class

    @Test
    fun staticFontWeightStepsWithoutFileChangeKeepTheLayout() {
        // 리디바탕: static, no bold file. Every weight uses the same file; only the synthetic stroke changes.
        val classes = (100..900 step 50).map { LayoutKeys.layoutWeight(it, variable = false, system = false, hasBoldFile = false) }.toSet()
        assertEquals(1, classes.size)
    }

    @Test
    fun staticFontWithBoldFileChangesOnlyAtTheFileBoundary() {
        fun c(w: Int) = LayoutKeys.layoutWeight(w, variable = false, system = false, hasBoldFile = true)
        assertEquals(c(400), c(450))
        assertEquals(c(400), c(550))
        assertNotEquals(c(550), c(600)) // body switches to the bold file
        assertEquals(c(600), c(900))
        // Lighter than regular renders as regular on a static file.
        assertEquals(c(100), c(400))
    }

    @Test
    fun variableAndSystemFontsKeepEveryWeight() {
        assertNotEquals(
            LayoutKeys.layoutWeight(400, variable = true, system = false, hasBoldFile = false),
            LayoutKeys.layoutWeight(450, variable = true, system = false, hasBoldFile = false),
        )
        assertNotEquals(
            LayoutKeys.layoutWeight(400, variable = false, system = true, hasBoldFile = false),
            LayoutKeys.layoutWeight(500, variable = false, system = true, hasBoldFile = false),
        )
        // Same normalised weight → same class.
        assertEquals(
            LayoutKeys.layoutWeight(410, variable = true, system = false, hasBoldFile = false),
            LayoutKeys.layoutWeight(400, variable = true, system = false, hasBoldFile = false),
        )
    }

    @Test
    fun weightClassMakesAStaticWeightChangeARepaint() {
        val w = LayoutKeys.layoutWeight(400, variable = false, system = false, hasBoldFile = false)
        val a = s.copy(fontWeight = w)
        val b = s.copy(fontWeight = LayoutKeys.layoutWeight(500, variable = false, system = false, hasBoldFile = false))
        assertFalse(LayoutKeys.layoutChanged(a, b, BookFormat.TXT))
        assertEquals(LayoutKeys.keyFor(a, BookFormat.TXT, "", g, density, font, 1), LayoutKeys.keyFor(b, BookFormat.TXT, "", g, density, font, 1))
    }

    // ---------------------------------------------------------------- TXT position signature / remap

    @Test
    fun textSignatureTracksOptionsThatMoveText() {
        val base = LayoutKeys.textSignature(s, BookFormat.TXT, "")
        assertNotNull(base)
        assertNull(LayoutKeys.textSignature(s, BookFormat.EPUB, ""))
        assertEquals(base, LayoutKeys.textSignature(s, BookFormat.TXT, ""))
        // Styling only: never remaps a position.
        assertEquals(base, LayoutKeys.textSignature(s.copy(txtEmphasizeHeadings = false, fontSizeSp = 30f, epubPublisherStyles = false), BookFormat.TXT, ""))
        for (t in listOf(
            s.copy(txtDetectChapters = false), s.copy(txtChapterRegex = "^제\\d+화"), s.copy(txtBlankLines = 3),
            s.copy(txtStripIndent = false), s.copy(txtJoinWrappedLines = 0), s.copy(txtReplaceRules = "a => b"),
        )) assertNotEquals(t.toString(), base, LayoutKeys.textSignature(t, BookFormat.TXT, ""))
        assertNotEquals(base, LayoutKeys.textSignature(s, BookFormat.TXT, "MS949"))
    }

    @Test
    fun textPositionEncoding() {
        val v = TextPositions.encode("abc123", 0.8312f)
        assertEquals("abc123" to 0.8312f, TextPositions.decode(v))
        assertNull(TextPositions.decode(null))
        assertNull(TextPositions.decode(""))
        assertNull(TextPositions.decode("nobar"))
        assertNull(TextPositions.decode("sig|"))
        assertNull(TextPositions.decode("|0.5"))
        assertNull(TextPositions.decode("sig|x"))
        assertEquals(1f, TextPositions.decode(TextPositions.encode("s", 7f))!!.second, 0f)
    }

    @Test
    fun remapOnlyWhenTheParseChanged() {
        val stored = TextPositions.encode("old", 0.83f)
        // Different parse: reopen at the stored char fraction (not section 250 clamped into 80 sections).
        assertEquals(0.83f, TextPositions.remapFraction(stored, "new", 250, 1200, 0.85f)!!, 1e-6f)
        // Same parse, EPUB, nothing recorded: use the saved coordinates as they are.
        assertNull(TextPositions.remapFraction(stored, "old", 250, 1200, 0.85f))
        assertNull(TextPositions.remapFraction(stored, null, 250, 1200, 0.85f))
        assertNull(TextPositions.remapFraction(null, "new", 250, 1200, 0.85f))
        assertNull(TextPositions.remapFraction("garbage", "new", 250, 1200, 0.85f))
        // The very start is the start of any parse (e.g. progress reset in the library).
        assertNull(TextPositions.remapFraction(stored, "new", 0, 0, 0f))
        // Progress changed elsewhere since (restore): the library's value wins.
        assertEquals(0.2f, TextPositions.remapFraction(stored, "new", 12, 40, 0.2f)!!, 1e-6f)
    }

    // ---------------------------------------------------------------- copy target (content:// imports)

    @Test
    fun copyNeverOverwritesAnotherBookWhenTheSizeIsUnknown() {
        val files = mapOf("novel.txt" to 5000L)
        val (name, reuse) = UriPaths.copyTarget("novel.txt", -1L) { files[it] }
        assertEquals("novel (2).txt", name)
        assertFalse(reuse)
        assertEquals("novel (2).txt" to false, UriPaths.copyTarget("novel.txt", 0L) { files[it] })
    }

    @Test
    fun copyReusesOnlyAMatchingSize() {
        val files = mapOf("a.epub" to 10L, "a (2).epub" to 20L, "a (3).epub" to -1L)
        assertEquals("a (2).epub" to true, UriPaths.copyTarget("a.epub", 20L) { files[it] })
        assertEquals("a (4).epub" to false, UriPaths.copyTarget("a.epub", 30L) { files[it] })
        assertEquals("b.txt" to false, UriPaths.copyTarget("b.txt", 30L) { files[it] })
    }

    @Test
    fun copyPastTheReuseSlotsStillPicksAFreeName() {
        val taken = (1..60).associate { UriPaths.numberedName("x.txt", it) to it.toLong() }
        // All 50 reuse slots hold other sizes: a fresh name, never slot 1.
        assertEquals("x (61).txt" to false, UriPaths.copyTarget("x.txt", 999L, maxReuse = 50) { taken[it] })
        // Slot 55 matches but lies past the reuse range: still a fresh name.
        assertEquals("x (61).txt" to false, UriPaths.copyTarget("x.txt", 55L, maxReuse = 50) { taken[it] })
        assertEquals("x (7).txt" to true, UriPaths.copyTarget("x.txt", 7L, maxReuse = 50) { taken[it] })
    }

    // ---------------------------------------------------------------- learned page keys

    @Test
    fun learnedKeysThrottleFreshPresses() {
        val f = RepeatFilter(RepeatFilter.LEARNED_MS, throttleFreshPresses = true)
        assertTrue(f.accept(0, 1000))
        // A bouncing fingerprint / function key: separate presses 80-120 ms apart = one page.
        assertFalse(f.accept(0, 1080))
        assertFalse(f.accept(0, 1200))
        assertFalse(f.accept(1, 1299))
        assertTrue(f.accept(0, 1300))
        // Normal keys keep accepting every fresh press.
        val n = RepeatFilter(RepeatFilter.NORMAL_MS)
        assertTrue(n.accept(0, 1000))
        assertTrue(n.accept(0, 1010))
        assertFalse(n.accept(1, 1100))
    }
}
