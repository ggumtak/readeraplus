package com.ggumtak.readeraplus.render.pdftext

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class PdfInternalsTest {
    private fun lexer(s: String) = PdfLexer(ArraySrc(s.toByteArray(Charsets.ISO_8859_1)), 0)

    private fun dict(s: String) = lexer(s).readObject() as PdfDict

    @Test
    fun lexerReadsNamesNumbersStringsAndRefs() {
        val d = dict("<< /A#20B 12 /N -.5 /P +3 /R 12 0 R /S (x\\)y) /H <4a4B> /Arr [1 2.5 (s) /N true null] /D << /K 1 >> >>")
        assertEquals(12, d.m["A B"])
        assertEquals(-0.5, d.num("N")!!, 1e-9)
        assertEquals(3.0, d.num("P")!!, 1e-9)
        val ref = d.raw("R") as PdfRef
        assertEquals(12, ref.num)
        assertEquals("x)y", String((d.raw("S") as PdfString).b))
        assertEquals("JK", String((d.raw("H") as PdfString).b))
        val arr = d.raw("Arr") as PdfArray
        assertEquals(6, arr.size)
        assertEquals(true, arr.raw(4))
        assertNull(arr.raw(5))
        assertEquals(1, d.dict("D")!!.int("K"))
    }

    @Test
    fun lexerSurvivesJunkAndDeepNesting() {
        val lx = lexer("<< /A ] >> ) ( unterminated")
        while (lx.next() != T_EOF) {
            // must terminate
        }
        try {
            lexer("[".repeat(100000)).readObject()
            fail("expected PdfFormatException")
        } catch (_: PdfFormatException) {
            // expected, and no StackOverflowError
        }
        try {
            lexer("<</A ".repeat(100000)).readObject()
            fail("expected PdfFormatException")
        } catch (_: PdfFormatException) {
            // expected
        }
        val l2 = lexer("1 0 R 2 0 R ".repeat(1000))
        var n = 0
        while (l2.readObject() !== EndMarker && n < 5000) n++
        assertEquals(2000, n)
    }

    @Test
    fun asciiFilters() {
        assertEquals("Hello", String(PdfFilters.asciiHex("48 65 6C 6C 6F>".toByteArray())))
        assertEquals("Hell`", String(PdfFilters.asciiHex("48656C6C6>".toByteArray()))) // odd digit padded with 0
        assertEquals("Man ", String(PdfFilters.ascii85("<~9jqo^~>".toByteArray())))
        assertEquals("Man ", String(PdfFilters.ascii85("9jqo^~>".toByteArray())))
        assertEquals("Ma", String(PdfFilters.ascii85("9jn~>".toByteArray())))
        assertArrayEquals(ByteArray(8), PdfFilters.ascii85("zz~>".toByteArray()))
    }

    @Test
    fun runLengthAndLzw() {
        val rl = byteArrayOf(2, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), -2, 'x'.code.toByte(), -128, 9)
        assertEquals("abcxxx", String(PdfFilters.runLength(rl)))
        // the example from the PDF specification (7.4.4.2)
        val lzw = intArrayOf(0x80, 0x0B, 0x60, 0x50, 0x22, 0x0C, 0x0C, 0x85, 0x01).map { it.toByte() }.toByteArray()
        val want = intArrayOf(45, 45, 45, 45, 45, 65, 45, 45, 45, 66).map { it.toByte() }.toByteArray()
        assertArrayEquals(want, PdfFilters.lzw(lzw, 1))
    }

    @Test
    fun flatePredictors() {
        val png = byteArrayOf(2, 1, 2, 3, 2, 1, 1, 1, 0, 9, 9, 9)
        val parms = dict("<< /Predictor 12 /Columns 3 >>")
        val out = PdfFilters.decodeChain(TestPdf.deflate(png), PdfName("FlateDecode"), parms)
        assertArrayEquals(byteArrayOf(1, 2, 3, 2, 3, 4, 9, 9, 9), out)
        val tiff = PdfFilters.decodeChain(
            TestPdf.deflate(byteArrayOf(1, 1, 1, 1, 2, 0, 0, 0)),
            PdfName("FlateDecode"),
            dict("<< /Predictor 2 /Columns 4 >>"),
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 2, 2, 2, 2), tiff)
    }

    @Test
    fun filterChainsAndDamagedFlate() {
        val data = "hello hello hello".toByteArray()
        val chain = TestPdf.deflate(data).let { z -> z.joinToString("") { "%02X".format(it) } + ">" }.toByteArray()
        val filters = PdfArray(null, arrayListOf(PdfName("ASCIIHexDecode"), PdfName("FlateDecode")))
        assertEquals("hello hello hello", String(PdfFilters.decodeChain(chain, filters, null)))
        val rnd = java.util.Random(3)
        val big = ByteArray(200_000).also { rnd.nextBytes(it) }
        val z = TestPdf.deflate(big)
        val half = PdfFilters.inflate(z.copyOf(z.size / 2))
        assertTrue(half.size > 1000 && half.size < big.size)
        assertArrayEquals(big.copyOf(1000), half.copyOf(1000))
        assertEquals(0, PdfFilters.inflate(ByteArray(50) { 0x7F }).size)
        try {
            PdfFilters.decodeChain(byteArrayOf(1), PdfName("DCTDecode"), null)
            fail("expected PdfFormatException")
        } catch (_: PdfFormatException) {
            // images are not decodable here
        }
    }

    @Test
    fun cmapBfCharBfRangeSurrogatesAndLigatures() {
        val cm = CMap()
        cm.parse(
            ("""
            3 beginbfchar
            <0001> <D83DDE00>
            <0002> <00660069>
            <0003> <41>
            endbfchar
            2 beginbfrange
            <0020> <0022> <0041>
            <0030> <0031> [<0058> <00590059>]
            endbfrange
            """).toByteArray(),
        )
        assertEquals("😀", cm.unicode(1))
        assertEquals("fi", cm.unicode(2))
        assertEquals("A", cm.unicode(3))
        assertEquals("A", cm.unicode(0x20))
        assertEquals("B", cm.unicode(0x21))
        assertEquals("C", cm.unicode(0x22))
        assertNull(cm.unicode(0x23))
        assertEquals("X", cm.unicode(0x30))
        assertEquals("YY", cm.unicode(0x31))
    }

    @Test
    fun cmapCodespaceAndCidRanges() {
        val cm = CMap()
        cm.parse(
            ("""
            2 begincodespacerange <00> <7F> <8000> <FFFF> endcodespacerange
            2 begincidrange <20> <7E> 1 <8140> <817E> 633 endcidrange
            1 begincidchar <8200> 700 endcidchar
            /WMode 1 def
            """).toByteArray(),
        )
        assertEquals(2, cm.codespaceCount)
        val b = byteArrayOf(0x41, 0x81.toByte(), 0x41, 0x00)
        assertEquals(1, cm.matchCodeLen(b, 0, 4))
        assertEquals(2, cm.matchCodeLen(b, 1, 4))
        assertEquals(0x41 - 0x20 + 1, cm.cid(0x41))
        assertEquals(634, cm.cid(0x8141))
        assertEquals(700, cm.cid(0x8200))
        assertEquals(-1, cm.cid(0x9999))
        assertEquals(1, cm.wmode)
    }

    @Test
    fun glyphNames() {
        assertEquals(95, PdfEncodings.asciiNames.size)
        assertEquals(96, PdfEncodings.latin1Names.size)
        assertEquals("A", PdfEncodings.nameToUnicode("A"))
        assertEquals(" ", PdfEncodings.nameToUnicode("space"))
        assertEquals("é", PdfEncodings.nameToUnicode("eacute"))
        assertEquals("ÿ", PdfEncodings.nameToUnicode("ydieresis"))
        assertEquals("€", PdfEncodings.nameToUnicode("Euro"))
        assertEquals("’", PdfEncodings.nameToUnicode("quoteright"))
        assertEquals("AB", PdfEncodings.nameToUnicode("uni00410042"))
        assertEquals("안", PdfEncodings.nameToUnicode("uniC548"))
        assertEquals("A", PdfEncodings.nameToUnicode("u0041"))
        assertEquals("😀", PdfEncodings.nameToUnicode("u1F600"))
        assertEquals("a", PdfEncodings.nameToUnicode("a.sc"))
        assertEquals("fi", PdfEncodings.nameToUnicode("f_i"))
        assertNull(PdfEncodings.nameToUnicode("g12"))
        assertNull(PdfEncodings.nameToUnicode("cid1234"))
        assertNull(PdfEncodings.nameToUnicode(".notdef"))
        assertNull(PdfEncodings.nameToUnicode(""))
        assertEquals("α", PdfEncodings.nameToUnicode("alpha"))
        assertEquals("Ω", PdfEncodings.nameToUnicode("Omega"))
    }

    @Test
    fun baseEncodings() {
        assertEquals(0x201C, PdfEncodings.winAnsi[0x93])
        assertEquals(0x20AC, PdfEncodings.winAnsi[0x80])
        assertEquals('A'.code, PdfEncodings.winAnsi['A'.code])
        assertEquals(0xE9, PdfEncodings.winAnsi[0xE9])
        assertEquals(0x2019, PdfEncodings.standard[0x27])
        assertEquals(0xFB01, PdfEncodings.standard[0xAE])
        assertEquals(0xC9, PdfEncodings.macRoman[0x83])
        assertEquals(0xE9, PdfEncodings.macRoman[0x8E])
        assertEquals(0, PdfEncodings.standard[0x80])
    }

    @Test
    fun macRomanMatchesJdkCharsetWhereAvailable() {
        assumeTrue(Charset.isSupported("x-MacRoman"))
        val cs = Charset.forName("x-MacRoman")
        // PDF's MacRoman differs from Apple's in a few places (currency sign, apple logo, nbsp handling)
        val allowed = setOf(0xDB, 0xF0)
        for (c in 0x80..0xFF) {
            if (c in allowed) continue
            val jdk = String(byteArrayOf(c.toByte()), cs)
            assertEquals("code 0x" + Integer.toHexString(c), jdk, String(Character.toChars(PdfEncodings.macRoman[c])))
        }
    }

    private fun cjkDoc(font: String, content: String): TestPdf {
        val pdf = TestPdf.doc(content)
        pdf.put(5, font)
        pdf.put(6, "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /Foo /DW 1000 >>")
        return pdf
    }

    @Test
    fun predefinedUcs2CMapNeedsNoToUnicode() {
        val pdf = cjkDoc(
            "<< /Type /Font /Subtype /Type0 /BaseFont /Foo /Encoding /UniKS-UCS2-H /DescendantFonts [6 0 R] >>",
            "BT /F1 10 Tf 10 100 Td <AC00B098> Tj ET",
        )
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("가나", g.text)
        assertEquals(10f, g.boxes[2] - g.boxes[0], 0.01f)
    }

    @Test
    fun utf16CMapHandlesSurrogatePairs() {
        val pdf = cjkDoc(
            "<< /Type /Font /Subtype /Type0 /BaseFont /Foo /Encoding /UniJIS-UTF16-H /DescendantFonts [6 0 R] >>",
            "BT /F1 10 Tf 10 100 Td <3042D83DDE00> Tj ET",
        )
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("あ😀", g.text)
    }

    @Test
    fun ksc_euc_and_uhc_decodeAsCharsets() {
        assumeTrue(Charset.isSupported("EUC-KR"))
        val pdf = cjkDoc(
            "<< /Type /Font /Subtype /Type0 /BaseFont /Foo /Encoding /KSC-EUC-H /DescendantFonts [6 0 R] >>",
            "BT /F1 10 Tf 10 100 Td <B0A1 41 B3AA> Tj ET",
        )
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("가A나", g.text)
    }

    @Test
    fun embeddedCMapWithVariableLengthCodes() {
        val cmap = """
            begincmap
            2 begincodespacerange <00> <7F> <8000> <FFFF> endcodespacerange
            1 begincidrange <8000> <80FF> 100 endcidrange
            endcmap
        """.trimIndent()
        val tu = """
            begincmap 2 begincodespacerange <00> <7F> <8000> <FFFF> endcodespacerange
            2 beginbfchar <41> <0041> <8001> <AC00> endbfchar endcmap
        """.trimIndent()
        val pdf = TestPdf.doc("BT /F1 10 Tf 10 100 Td <41 8001 41> Tj ET")
        pdf.put(5, "<< /Type /Font /Subtype /Type0 /BaseFont /Foo /Encoding 7 0 R /DescendantFonts [6 0 R] /ToUnicode 8 0 R >>")
        pdf.put(6, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Foo /DW 500 /W [101 [1000]] >>")
        pdf.putStream(7, "/Type /CMap", cmap)
        pdf.putStream(8, "", tu)
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("A가A", g.text)
        // code 0x41 -> cid 0 (not mapped) width DW 500 -> 5pt; code 0x8001 -> cid 101 -> 1000 -> 10pt
        assertEquals(5f, g.boxes[2] - g.boxes[0], 0.01f)
        assertEquals(10f, g.boxes[6] - g.boxes[4], 0.01f)
        assertEquals(15f, g.boxes[8] - 10f, 0.01f)
    }

    @Test
    fun toUnicodeOverridesEncodingForSimpleFonts() {
        val tu = "begincmap 1 begincodespacerange <00> <FF> endcodespacerange 2 beginbfchar <41> <0058> <42> <00660066> endbfchar " +
            "1 beginbfrange <43> <44> <0061> endbfrange endcmap"
        val pdf = TestPdf.doc("BT /F1 10 Tf 10 100 Td (ABCDE) Tj ET")
        pdf.put(5, "<< /Type /Font /Subtype /TrueType /BaseFont /Foo /Encoding /WinAnsiEncoding /ToUnicode 7 0 R >>")
        pdf.putStream(7, "", tu)
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("Xffabe".replace("be", "bE"), g.text)
        assertNotNull(g)
    }

    @Test
    fun inheritedResourcesAndNestedTrees() {
        val pdf = TestPdf()
        pdf.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.put(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources << /Font << /F1 5 0 R >> >> /Rotate 90 >>")
        pdf.put(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 200] /Contents 4 0 R >>")
        pdf.putStream(4, "", "BT /F1 10 Tf 10 20 Td (in) Tj ET")
        pdf.put(5, TestPdf.HELV_FONT)
        val r = TestPdf.open(pdf.classic())
        assertArrayEquals(floatArrayOf(200f, 100f), r.pageSize(0), 0.01f)
        assertEquals("in", r.page(0).text)
    }
}
