package com.ggumtak.readeraplus.format.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubPathsTest {
    @Test
    fun normalize() {
        assertEquals("a/c", EpubPaths.normalize("a/b/../c"))
        assertEquals("a/c", EpubPaths.normalize("./a/./c"))
        assertEquals("a/b", EpubPaths.normalize("/a//b"))
        assertEquals("c", EpubPaths.normalize("../../c"))
        assertEquals("a/b", EpubPaths.normalize("a\\b"))
        assertEquals("OEBPS/Text/ch1.xhtml", EpubPaths.normalize("OEBPS/Text/ch1.xhtml"))
        assertEquals("", EpubPaths.normalize(".."))
    }

    @Test
    fun resolve() {
        assertEquals("OEBPS/Images/a.jpg", EpubPaths.resolve("OEBPS/Text/", "../Images/a.jpg"))
        assertEquals("OEBPS/Text/ch 1.xhtml", EpubPaths.resolve("OEBPS/Text/", "ch%201.xhtml#frag"))
        assertEquals("OEBPS/Text/ch%201.xhtml", EpubPaths.resolve("OEBPS/Text/", "ch%201.xhtml", decode = false))
        assertEquals("Images/b.png", EpubPaths.resolve("OEBPS/Text/", "/Images/b.png"))
        assertEquals("x.html", EpubPaths.resolve("", "x.html?y=1#z"))
        assertEquals("OEBPS/한글.xhtml", EpubPaths.resolve("OEBPS/", "%ED%95%9C%EA%B8%80.xhtml"))
        assertEquals("OEBPS/a%zz.html", EpubPaths.resolve("OEBPS/", "a%zz.html")) // malformed escape stays
    }

    @Test
    fun fragmentsAndSchemes() {
        assertEquals("sec 1", EpubPaths.fragment("a.html#sec%201"))
        assertNull(EpubPaths.fragment("a.html"))
        assertNull(EpubPaths.fragment("a.html#"))
        assertEquals("a.html", EpubPaths.stripFragment("a.html#x"))
        assertTrue(EpubPaths.hasScheme("http://example.com"))
        assertTrue(EpubPaths.hasScheme("mailto:a@b"))
        assertTrue(EpubPaths.hasScheme("data:image/png;base64,xx"))
        assertFalse(EpubPaths.hasScheme("ch1.xhtml#a:b"))
        assertFalse(EpubPaths.hasScheme("../Text/c.xhtml"))
        assertFalse(EpubPaths.hasScheme("#note"))
        assertFalse(EpubPaths.hasScheme(":x"))
    }

    @Test
    fun extensions() {
        assertEquals("jpg", EpubPaths.extension("a/b.JPG"))
        assertEquals("", EpubPaths.extension("a.dir/file"))
        assertTrue(EpubPaths.isImagePath("x/cover.jpeg"))
        assertTrue(EpubPaths.isHtmlPath("x/ch.xhtml"))
        assertFalse(EpubPaths.isHtmlPath("x/style.css"))
    }

    @Test
    fun decodeBomsAndDeclarations() {
        val s = "<p>가나다 abc</p>"
        assertEquals(s, EpubText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray()))
        assertEquals(s, EpubText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
        assertEquals(s, EpubText.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)))
        assertEquals(s, EpubText.decode(s.toByteArray(Charsets.UTF_16LE)))
        assertEquals(s, EpubText.decode(s.toByteArray(Charsets.UTF_16BE)))
        val cp949 = EpubText.cp949()!!
        val kr = "<?xml version=\"1.0\" encoding=\"euc-kr\"?><p>똠방각하 햏 쐈다</p>"
        assertEquals(kr, EpubText.decode(kr.toByteArray(cp949)))
        // undeclared but invalid as UTF-8 → CP949
        val kr2 = "<p>똠방각하 햏 쐈다</p>"
        assertEquals(kr2, EpubText.decode(kr2.toByteArray(cp949)))
        val latin = "<?xml version='1.0' encoding='ISO-8859-1'?><p>café</p>"
        assertEquals(latin, EpubText.decode(latin.toByteArray(Charsets.ISO_8859_1)))
        assertEquals("", EpubText.decode(ByteArray(0)))
    }

    @Test
    fun utf8Validation() {
        val ok = "가나 abc 😀".toByteArray()
        assertTrue(EpubText.isValidUtf8(ok, ok.size))
        assertTrue(EpubText.isValidUtf8(ok, ok.size - 1)) // truncated final sequence tolerated
        assertFalse(EpubText.isValidUtf8(byteArrayOf(0xC0.toByte(), 0x80.toByte()), 2))
        assertFalse(EpubText.isValidUtf8(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), 3))
        assertFalse(EpubText.isValidUtf8(byteArrayOf(0xB0.toByte(), 0x41), 2))
    }

    @Test
    fun cssDecoding() {
        assertEquals("p{}", EpubText.decodeCss(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "p{}".toByteArray()))
        val cs = "@charset \"euc-kr\"; .가{}"
        assertEquals(cs, EpubText.decodeCss(cs.toByteArray(EpubText.cp949()!!)))
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("ch10.html", "ch2.html", "ch1.html", "Ch3.html").sortedWith(EpubDocuments.NaturalOrder)
        assertEquals(listOf("ch1.html", "ch2.html", "Ch3.html", "ch10.html"), sorted)
    }
}
