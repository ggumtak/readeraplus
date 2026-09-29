package com.ggumtak.readeraplus.format.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCssTest {
    @Test
    fun parsesRulesAndIndexes() {
        val sheet = CssParser.parse(
            """
            /* comment { with braces } */
            p { text-indent: 1em; margin: 0 }
            .center, h2.title { text-align: center }
            #intro { font-weight: bold }
            * { margin: 0; padding: 0 }
            @font-face { font-family: X; src: url(a.ttf) }
            @page { margin: 5pt }
            @media print { p { display: none } }
            @media screen, amzn-kf8 { .scr { font-style: italic } }
            @import url("more.css");
            p::first-letter { font-size: 3em }
            p:first-child { text-indent: 0 }
            a:hover { text-decoration: underline }
            div > p + p { text-indent: 2em }
            [data-x] { display: none }
            h3 ~ p { color: red }
            """.trimIndent(),
        )
        assertTrue(sheet.byTag.containsKey("p"))
        assertTrue(sheet.byClass.containsKey("center"))
        assertTrue(sheet.byClass.containsKey("title"))
        assertTrue(sheet.byId.containsKey("intro"))
        assertTrue(sheet.byClass.containsKey("scr"))
        assertEquals(1, sheet.universal.size)
        assertEquals(listOf("more.css"), sheet.imports)
        // @media print rule dropped, pseudo-element/hover/attr/general sibling dropped
        val pRules = sheet.byTag["p"]!!
        assertTrue(pRules.none { it.decl.displayNone })
        assertEquals(3, pRules.size) // p, p:first-child, div > p + p
        assertTrue(pRules.any { it.selector.parts.size == 3 })
        assertTrue(pRules.any { it.selector.subject.firstChild })
    }

    @Test
    fun selectors() {
        val s = CssParser.parseSelector("div.chapter > p.first + p")!!
        assertEquals(3, s.parts.size)
        assertEquals("div", s.parts[0].tag)
        assertEquals("chapter", s.parts[0].classes[0])
        assertEquals('>', s.combinators[0])
        assertEquals('+', s.combinators[1])
        assertFalse(s.simple)
        assertTrue(CssParser.parseSelector("p.a.b")!!.simple)
        assertNull(CssParser.parseSelector("p::before"))
        assertNull(CssParser.parseSelector("p:nth-child(2)"))
        assertNull(CssParser.parseSelector("input[type=text]"))
        assertNull(CssParser.parseSelector("> p"))
        assertNotNull(CssParser.parseSelector("svg|image"))
        assertEquals("html", CssParser.parseSelector(":root")!!.subject.tag)
        assertEquals(null, CssParser.parseSelector("*.x")!!.subject.tag)
    }

    @Test
    fun declarations() {
        val d = CssParser.parseInline(
            "text-align: center; text-indent: 0; font-weight: 700; font-style: italic; font-size: 120%; " +
                "margin: 2em 0 1.5em 1em; padding-left: 16px; page-break-before: always; " +
                "text-decoration: underline line-through; vertical-align: super; white-space: pre-wrap; " +
                "list-style: none inside; color: red; font-family: serif",
        )!!
        assertEquals(CssDecl.A_CENTER, d.align)
        assertFalse(d.indent)
        assertTrue(d.bold)
        assertTrue(d.italic)
        assertEquals(1.2f, d.size, 0.001f)
        assertFalse(d.sizeAbs)
        assertEquals(2f, d.marginTop, 0.001f)
        assertEquals(1.5f, d.marginBottom, 0.001f)
        assertEquals(1f, d.marginLeft, 0.001f)
        assertEquals(1f, d.paddingLeft, 0.001f)
        assertTrue(d.breakBefore)
        assertTrue(d.underline)
        assertTrue(d.strike)
        assertEquals(1, d.vAlign)
        assertTrue(d.pre)
        assertEquals(CssDecl.LS_NONE, d.listStyle)
        assertNull(CssParser.parseInline("color: red; font-family: x; line-height: 2"))
    }

    @Test
    fun fontSizes() {
        fun size(v: String) = CssParser.parseInline("font-size: $v")
        assertEquals(1.5f, size("1.5em")!!.size, 0.001f)
        assertEquals(0.7f, size("0.3em")!!.size, 0.001f) // clamp
        assertEquals(2f, size("400%")!!.size, 0.001f)
        assertTrue(size("1.2rem")!!.sizeAbs)
        assertTrue(size("x-large")!!.sizeAbs)
        assertNull(size("12px"))
        assertNull(size("10pt"))
        assertNull(size("inherit"))
    }

    @Test
    fun importantBeatsSpecificity() {
        val sheet = CssParser.parse("p { font-weight: bold !important } p.x { font-weight: normal }")
        val c = CssCascade()
        c.add(listOf(sheet))
        val d = c.compute("p", null, "x", CssCascade.Matcher { true })
        assertTrue(d.bold)
    }

    @Test
    fun specificityAndOrder() {
        val sheet = CssParser.parse(
            ".a { text-align: right } p { text-align: center } #i { text-align: left } p { text-align: justify }",
        )
        val c = CssCascade()
        c.add(listOf(sheet))
        val m = CssCascade.Matcher { true }
        assertEquals(CssDecl.A_JUSTIFY, c.compute("p", null, null, m).align)
        assertEquals(CssDecl.A_RIGHT, c.compute("p", null, "a", m).align)
        assertEquals(CssDecl.A_LEFT, c.compute("p", "i", "a", m).align)
        // cached result is stable
        assertEquals(CssDecl.A_RIGHT, c.compute("p", null, "a", m).align)
    }

    @Test
    fun laterSheetWinsOnTie() {
        val c = CssCascade()
        c.add(listOf(CssParser.parse("p { text-align: center }")))
        c.add(listOf(CssParser.parse("p { text-align: right }")))
        assertEquals(CssDecl.A_RIGHT, c.compute("p", null, null, CssCascade.Matcher { true }).align)
    }

    @Test
    fun hasClass() {
        assertTrue(CssCascade.hasClass("a  bb c", "bb"))
        assertFalse(CssCascade.hasClass("abb c", "bb"))
        assertFalse(CssCascade.hasClass("bbx", "bb"))
        assertTrue(CssCascade.hasClass("x\tbb", "bb"))
    }

    @Test
    fun garbageNeverThrows() {
        for (s in listOf("{", "}", "p {", "p { color", "@media {", "@import", "p{a:b;;;:;}", "/* x", "\"", "a{b:\"}\"}")) {
            CssParser.parse(s)
        }
        assertNull(CssParser.parseInline(";;:"))
    }

    @Test
    fun listMarkers() {
        assertEquals("c", ListMarkers.ordered(3, CssDecl.LS_LOWER_ALPHA))
        assertEquals("AA", ListMarkers.ordered(27, CssDecl.LS_UPPER_ALPHA))
        assertEquals("xiv", ListMarkers.ordered(14, CssDecl.LS_LOWER_ROMAN))
        assertEquals("다", ListMarkers.ordered(3, CssDecl.LS_HANGUL))
        assertEquals("ㄹ", ListMarkers.ordered(4, CssDecl.LS_HANGUL_CONSONANT))
        assertEquals("7", ListMarkers.ordered(7, 0))
    }
}
