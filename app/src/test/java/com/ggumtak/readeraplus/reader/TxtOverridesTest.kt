package com.ggumtak.readeraplus.reader

import com.ggumtak.readeraplus.data.TxtOverride
import com.ggumtak.readeraplus.engine.Align
import com.ggumtak.readeraplus.format.ParseOptions
import com.ggumtak.readeraplus.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtOverridesTest {
    private val global = ReaderSettings(
        fontSizeSp = 22f, align = Align.JUSTIFY, txtBlankLines = ParseOptions.BLANK_AUTO, txtStripIndent = true,
        txtJoinWrappedLines = 1, txtDetectChapters = true, txtChapterRegex = "^제\\d+화", txtEmphasizeHeadings = true,
        txtReplaceRules = "광고 =>",
    )

    @Test
    fun nullOrEmptyOverrideKeepsTheSameObject() {
        assertSame(global, global.withTxt(null))
        assertSame(global, global.withTxt(TxtOverride()))
    }

    @Test
    fun everyFieldReplacesItsGlobalOption() {
        val o = TxtOverride(
            blankLines = ParseOptions.BLANK_KEEP, stripIndent = false, joinWrapped = 0, detectChapters = false,
            chapterRegex = "", emphasizeHeadings = false, replaceRules = "a => b",
        )
        val eff = global.withTxt(o)
        assertEquals(ParseOptions.BLANK_KEEP, eff.txtBlankLines)
        assertEquals(false, eff.txtStripIndent)
        assertEquals(0, eff.txtJoinWrappedLines)
        assertEquals(false, eff.txtDetectChapters)
        // "" is a value of its own (no chapter rule for this book), not "use the global rule".
        assertEquals("", eff.txtChapterRegex)
        assertEquals(false, eff.txtEmphasizeHeadings)
        assertEquals("a => b", eff.txtReplaceRules)
        // Nothing but the TXT options changes.
        assertEquals(global, eff.copy(
            txtBlankLines = global.txtBlankLines, txtStripIndent = global.txtStripIndent,
            txtJoinWrappedLines = global.txtJoinWrappedLines, txtDetectChapters = global.txtDetectChapters,
            txtChapterRegex = global.txtChapterRegex, txtEmphasizeHeadings = global.txtEmphasizeHeadings,
            txtReplaceRules = global.txtReplaceRules,
        ))
    }

    @Test
    fun nullFieldsFollowTheGlobalValue() {
        val eff = global.withTxt(TxtOverride(stripIndent = false))
        assertEquals(false, eff.txtStripIndent)
        assertEquals(global.txtReplaceRules, eff.txtReplaceRules)
        assertEquals(global.txtChapterRegex, eff.txtChapterRegex)
        assertEquals(global.txtBlankLines, eff.txtBlankLines)
        // A later change of the global rules reaches the book (its override doesn't pin them).
        val newGlobal = global.copy(txtReplaceRules = "x => y")
        assertEquals("x => y", newGlobal.withTxt(TxtOverride(stripIndent = false)).txtReplaceRules)
    }

    @Test
    fun overrideEqualToTheGlobalValuesIsTheSameObject() {
        val same = TxtOverride(stripIndent = global.txtStripIndent, replaceRules = global.txtReplaceRules)
        assertSame(global, global.withTxt(same))
    }

    @Test
    fun effectiveSettingsCompareEqualWhereverComputed() {
        // onResume compares Settings.reader.withTxt(bookOverride) with the session's settings: two merges of equal
        // inputs must be equal, or every resume would re-lay out the book.
        val o = TxtOverride(blankLines = ParseOptions.BLANK_REMOVE_ALL, replaceRules = "r =>")
        assertEquals(global.withTxt(o), global.copy().withTxt(o.copy()))
        assertEquals(global.withTxt(o).parseOptions("MS949"), global.withTxt(o).parseOptions("MS949"))
        assertNotEquals(global.parseOptions(), global.withTxt(o).parseOptions())
        // Saving the effective TXT values as the new defaults and clearing the override changes nothing.
        val asDefaults = global.withTxt(o)
        assertEquals(global.withTxt(o), asDefaults.withTxt(null))
        assertEquals(asDefaults, asDefaults.withTxt(o))
    }

    @Test
    fun jsonRoundTrip() {
        val o = TxtOverride(
            blankLines = 2, stripIndent = true, joinWrapped = 2, detectChapters = true,
            chapterRegex = "^(\\d+)화 \"x\"", emphasizeHeadings = false, replaceRules = "## 광고\n^.*무단.*$ =>\n#- a => b",
        )
        assertEquals(o, TxtOverride.fromJson(o.toJson()))
        val partial = TxtOverride(replaceRules = "")
        assertEquals(partial, TxtOverride.fromJson(partial.toJson()))
        assertEquals("{}", TxtOverride().toJson())
        assertNull(TxtOverride.fromJson(TxtOverride().toJson()))
    }

    @Test
    fun jsonIsTolerant() {
        assertNull(TxtOverride.fromJson(null))
        assertNull(TxtOverride.fromJson(""))
        assertNull(TxtOverride.fromJson("not json"))
        assertNull(TxtOverride.fromJson("[1,2]"))
        assertNull(TxtOverride.fromJson("{\"unknown\": 1}"))
        val o = TxtOverride.fromJson("{\"blankLines\": 9, \"joinWrapped\": -3, \"stripIndent\": \"yes\", \"detectChapters\": false, \"chapterRegex\": 5}")!!
        assertEquals(3, o.blankLines)
        assertEquals(0, o.joinWrapped)
        assertNull(o.stripIndent)
        assertEquals(false, o.detectChapters)
        assertNull(o.chapterRegex)
        assertNull(TxtOverride.fromJson("{\"blankLines\": 1.5}"))
        assertTrue(TxtOverride(emphasizeHeadings = null).isEmpty)
    }
}
