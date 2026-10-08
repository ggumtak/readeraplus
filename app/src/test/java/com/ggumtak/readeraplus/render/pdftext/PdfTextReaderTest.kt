package com.ggumtak.readeraplus.render.pdftext

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfTextReaderTest {
    private val eps = 0.01f

    private fun assertBox(g: PageGlyphs, i: Int, l: Float, t: Float, r: Float, b: Float) {
        val o = i * 4
        assertEquals("left of char $i", l, g.boxes[o], eps)
        assertEquals("top of char $i", t, g.boxes[o + 1], eps)
        assertEquals("right of char $i", r, g.boxes[o + 2], eps)
        assertEquals("bottom of char $i", b, g.boxes[o + 3], eps)
    }

    private fun textOf(bytes: ByteArray): String {
        val r = TestPdf.open(bytes)
        try {
            return r.page(0).text
        } finally {
            r.close()
        }
    }

    @Test
    fun classicXrefHelloWorld() {
        val bytes = TestPdf.doc("BT /F1 12 Tf 100 700 Td (Hello World) Tj ET").classic()
        val r = TestPdf.open(bytes)
        assertEquals(1, r.pageCount)
        assertArrayEquals(floatArrayOf(612f, 792f), r.pageSize(0), eps)
        val g = r.page(0)
        assertEquals("Hello World", g.text)
        assertArrayEquals(intArrayOf(0), g.lineStarts)
        assertEquals(44, g.boxes.size)
        // 12 pt font, 0.5 em glyphs, baseline y=700 (user) -> top-left origin: ascent .8, descent -.2
        assertBox(g, 0, 100f, 792f - 709.6f, 106f, 792f - 697.6f)
        assertBox(g, 6, 136f, 792f - 709.6f, 142f, 792f - 697.6f)
        r.close()
    }

    @Test
    fun flateContentObjectStreamAndXrefStream() {
        val pdf = TestPdf.doc("BT /F1 10 Tf 50 100 Td (Compressed text) Tj ET")
        // replace the content with a Flate one
        pdf.putStream(4, "", "BT /F1 10 Tf 50 100 Td (Compressed text) Tj ET".toByteArray(), flate = true)
        val bytes = pdf.xrefStream(useObjStm = true)
        assertEquals("Compressed text", textOf(bytes))
        val noObjStm = pdf.xrefStream(useObjStm = false)
        assertEquals("Compressed text", textOf(noObjStm))
    }

    private fun koreanDoc(content: String): TestPdf {
        val cmap = """
            /CIDInit /ProcSet findresource begin 12 dict begin begincmap
            /CMapName /Adobe-Identity-UCS def /CMapType 2 def
            1 begincodespacerange <0000> <FFFF> endcodespacerange
            2 beginbfchar
            <0001> <C548>
            <0002> <B155>
            endbfchar
            1 beginbfrange
            <0003> <0005> [<D558> <C138> <C694>]
            endbfrange
            endcmap CMapName currentdict /CMap defineresource pop end end
        """.trimIndent()
        val pdf = TestPdf.doc(content)
        pdf.put(5, "<< /Type /Font /Subtype /Type0 /BaseFont /Test /Encoding /Identity-H /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>")
        pdf.put(6, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Test /DW 1000 /W [1 [500] 2 5 1000] /FontDescriptor 8 0 R >>")
        pdf.putStream(7, "", cmap)
        pdf.put(8, "<< /Type /FontDescriptor /FontName /Test /Ascent 880 /Descent -120 /Flags 4 >>")
        return pdf
    }

    @Test
    fun type0IdentityHKoreanWithToUnicodeAndWidths() {
        val g = TestPdf.open(koreanDoc("BT /F1 20 Tf 50 700 Td <0001 0002 0003 0004> Tj <0005> Tj ET").classic()).page(0)
        assertEquals("\uC548\uB155\uD558\uC138\uC694", g.text)
        // cid 1 has width 500 -> 10 pt, cids 2..5 have 1000 -> 20 pt; ascent .88 / descent -.12 from the descriptor
        val top = 792f - 717.6f
        val bottom = 792f - 697.6f
        assertBox(g, 0, 50f, top, 60f, bottom)
        assertBox(g, 1, 60f, top, 80f, bottom)
        assertBox(g, 2, 80f, top, 100f, bottom)
        assertBox(g, 3, 100f, top, 120f, bottom)
        assertBox(g, 4, 120f, top, 140f, bottom)
    }

    @Test
    fun type0UnmappedCodeAdvancesAndGapBecomesSpace() {
        // code 9 has no Unicode: no char, but the pen moves 20 pt, which is a gap of 4 em-quarters -> one space
        val g = TestPdf.open(koreanDoc("BT /F1 20 Tf 50 700 Td <0001 0002 0009 0003> Tj ET").classic()).page(0)
        assertEquals("\uC548\uB155 \uD558", g.text)
        assertEquals(80f, g.boxes[2 * 4], eps) // the inserted space sits at the end of the previous glyph
        assertEquals(100f, g.boxes[3 * 4], eps)
    }

    @Test
    fun tjKerningInsertsSpaceAndSmallKernDoesNot() {
        val g = TestPdf.open(
            TestPdf.doc("BT /F1 10 Tf 100 700 Td [(Hello) -300 (World)] TJ 0 -20 Td [(A) -100 (B)] TJ ET").classic(),
        ).page(0)
        // -300/1000 * 10 = 3 pt gap > 2.5 pt -> space; -100 -> 1 pt gap -> none
        assertEquals("Hello World\nAB", g.text)
        assertArrayEquals(intArrayOf(0, 12), g.lineStarts)
        // inserted space and '\n' are zero width at the end of the previous glyph
        assertEquals(g.boxes[4 * 4 + 2], g.boxes[5 * 4], eps)
        assertEquals(g.boxes[5 * 4], g.boxes[5 * 4 + 2], eps)
        assertEquals(g.boxes[10 * 4 + 2], g.boxes[11 * 4], eps)
        assertEquals(g.boxes[11 * 4], g.boxes[11 * 4 + 2], eps)
    }

    @Test
    fun twoLinesWithTdAndTStar() {
        val g = TestPdf.open(
            TestPdf.doc("BT /F1 12 Tf 14 TL 100 700 Td (Line1) Tj T* (Line2) Tj (x) ' ET").classic(),
        ).page(0)
        assertEquals("Line1\nLine2\nx", g.text)
        assertArrayEquals(intArrayOf(0, 6, 12), g.lineStarts)
        assertEquals(g.text.length * 4, g.boxes.size)
    }

    @Test
    fun rotate90SwapsSizeAndCoordinates() {
        val bytes = TestPdf.doc("BT /F1 12 Tf 100 700 Td (A) Tj ET", pageExtra = "/Rotate 90").classic()
        val r = TestPdf.open(bytes)
        assertArrayEquals(floatArrayOf(792f, 612f), r.pageSize(0), eps)
        val g = r.page(0)
        assertEquals("A", g.text)
        // user box x 100..106, y 697.6..709.6 -> displayed (y - y0, x - x0)
        assertBox(g, 0, 697.6f, 100f, 709.6f, 106f)
    }

    @Test
    fun rotateNormalisationAndOtherAngles() {
        val sizes = mapOf("-90" to 270, "270" to 270, "180" to 180, "450" to 90, "0" to 0)
        for ((rot, _) in sizes) {
            val r = TestPdf.open(TestPdf.doc("BT /F1 12 Tf 100 700 Td (A) Tj ET", pageExtra = "/Rotate $rot").classic())
            val s = r.pageSize(0)
            val swapped = rot == "-90" || rot == "270" || rot == "450"
            assertArrayEquals("rot $rot", if (swapped) floatArrayOf(792f, 612f) else floatArrayOf(612f, 792f), s, eps)
            assertEquals("rot $rot", "A", r.page(0).text)
        }
        // 180: x'' = x1 - x, y'' = y - y0
        val g180 = TestPdf.open(TestPdf.doc("BT /F1 12 Tf 100 700 Td (A) Tj ET", pageExtra = "/Rotate 180").classic()).page(0)
        assertBox(g180, 0, 612f - 106f, 697.6f, 612f - 100f, 709.6f)
        // 270: (y1 - y, x1 - x)
        val g270 = TestPdf.open(TestPdf.doc("BT /F1 12 Tf 100 700 Td (A) Tj ET", pageExtra = "/Rotate 270").classic()).page(0)
        assertBox(g270, 0, 792f - 709.6f, 612f - 106f, 792f - 697.6f, 612f - 100f)
    }

    @Test
    fun cropBoxAndInheritedMediaBox() {
        val pdf = TestPdf()
        pdf.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.put(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 600 800] /Rotate 0 /Resources << /Font << /F1 5 0 R >> >> >>")
        pdf.put(3, "<< /Type /Page /Parent 2 0 R /Contents 4 0 R /CropBox [100 100 400 500] >>")
        pdf.putStream(4, "", "BT /F1 10 Tf 150 200 Td (Hi) Tj ET")
        pdf.put(5, TestPdf.HELV_FONT)
        val r = TestPdf.open(pdf.classic())
        assertArrayEquals(floatArrayOf(300f, 400f), r.pageSize(0), eps)
        val g = r.page(0)
        assertEquals("Hi", g.text)
        // user (150, 200) -> (150-100, 500-200) = (50, 300) baseline; ascent 8
        assertBox(g, 0, 50f, 300f - 8f, 55f, 300f + 2f)
    }

    @Test
    fun formXObjectWithMatrixAndNestedResources() {
        val pdf = TestPdf.doc(
            "q 1 0 0 1 0 -100 cm /Fm1 Do Q BT /F1 12 Tf 10 20 Td (Top) Tj ET",
            resExtra = "/XObject << /Fm1 6 0 R >>",
        )
        pdf.putStream(
            6,
            "/Type /XObject /Subtype /Form /BBox [0 0 612 792] /Matrix [1 0 0 1 50 0] /Resources << /Font << /F2 5 0 R >> >>",
            "BT /F2 12 Tf 100 700 Td (Hi) Tj ET",
        )
        val g = TestPdf.open(pdf.classic()).page(0)
        // form text baseline: (100 + 50, 700 - 100); it comes first in content order, "Top" is lower on the page
        assertEquals("Hi\nTop", g.text)
        assertBox(g, 0, 150f, 792f - 609.6f, 156f, 792f - 597.6f)
        assertEquals(10f, g.boxes[3 * 4], eps)
    }

    @Test
    fun selfReferencingFormTerminates() {
        val pdf = TestPdf.doc("/Fm1 Do BT /F1 12 Tf 10 700 Td (ok) Tj ET", resExtra = "/XObject << /Fm1 6 0 R >>")
        pdf.putStream(6, "/Type /XObject /Subtype /Form /Resources << /XObject << /Fm1 6 0 R >> >>", "/Fm1 Do /Fm1 Do")
        assertEquals("ok", textOf(pdf.classic()))
    }

    @Test
    fun differencesAndUniNames() {
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /Foo /FirstChar 32 /LastChar 126 /Widths [${TestPdf.HALF_WIDTHS}] " +
            "/Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [65 /uni0042 /fi /Euro /g12 /u1F600 /f_i /A.alt] >> >>"
        val g = TestPdf.open(TestPdf.doc("BT /F1 10 Tf 0 100 Td (ABCDEFGH) Tj ET", font = font).classic()).page(0)
        // A->B, B->fi (ligature), C->Euro, D (g12) dropped (its 5 pt gap becomes a space), E->U+1F600, F->fi, G->A, H plain
        assertEquals("Bfi\u20AC \uD83D\uDE00fiAH", g.text)
        // the "fi" glyph (code 66) spans x 5..10; its two chars share the box evenly
        assertEquals(5f, g.boxes[1 * 4], eps)
        assertEquals(7.5f, g.boxes[1 * 4 + 2], eps)
        assertEquals(7.5f, g.boxes[2 * 4], eps)
        assertEquals(10f, g.boxes[2 * 4 + 2], eps)
    }

    @Test
    fun standardEncodingDefaultAndMacRoman() {
        val std = "<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>"
        val g = TestPdf.open(TestPdf.doc("BT /F1 10 Tf 0 100 Td (a\\047b\\256) Tj ET", font = std).classic()).page(0)
        assertEquals("a\u2019bfi", g.text)
        val mac = "<< /Type /Font /Subtype /TrueType /BaseFont /Foo /Encoding /MacRomanEncoding >>"
        val g2 = TestPdf.open(TestPdf.doc("BT /F1 10 Tf 0 100 Td (\\216\\237) Tj ET", font = mac).classic()).page(0)
        assertEquals("éü", g2.text)
    }

    @Test
    fun standardFontsWithoutWidthsUseBuiltInMetrics() {
        val font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        val g = TestPdf.open(TestPdf.doc("BT /F1 10 Tf 0 100 Td (Wi) Tj ET", font = font).classic()).page(0)
        assertEquals(9.44f, g.boxes[2] - g.boxes[0], eps)
        assertEquals(9.44f, g.boxes[4], eps)
    }

    @Test
    fun brokenXrefOffsetsAreRecoveredByScanning() {
        val pdf = TestPdf.doc("BT /F1 12 Tf 100 700 Td (Recovered) Tj ET")
        assertEquals("Recovered", textOf(pdf.classic(offsetShift = 7)))
        assertEquals("Recovered", textOf(pdf.classic(startxrefOk = false)))
    }

    @Test
    fun missingXrefAndTrailerRecoveredFromXrefStreamAndObjStm() {
        val pdf = TestPdf.doc("BT /F1 12 Tf 100 700 Td (Scanned) Tj ET")
        assertEquals("Scanned", textOf(pdf.xrefStream(useObjStm = true, withStartxref = false)))
        // cut off the xref stream (object 7) completely: the catalog is found inside the object stream (object 6)
        val full = pdf.xrefStream(useObjStm = true)
        val idx = String(full, Charsets.ISO_8859_1).lastIndexOf("7 0 obj")
        assertTrue(idx > 0)
        assertEquals("Scanned", textOf(full.copyOf(idx)))
    }

    @Test
    fun trailerWithoutXrefIsFound() {
        val classic = TestPdf.doc("BT /F1 12 Tf 100 700 Td (NoXref) Tj ET").classic()
        val s = String(classic, Charsets.ISO_8859_1)
        val cut = s.indexOf("xref\n0 ")
        val trailer = s.indexOf("trailer")
        val damaged = (s.substring(0, cut) + s.substring(trailer)).toByteArray(Charsets.ISO_8859_1)
        assertEquals("NoXref", textOf(damaged))
    }

    @Test
    fun encryptedThrows() {
        val bytes = TestPdf.doc("BT /F1 12 Tf 100 700 Td (Secret) Tj ET")
            .put(9, "<< /Filter /Standard /V 1 /R 2 /O (x) /U (y) /P -4 >>")
            .classic(trailerExtra = "/Encrypt 9 0 R")
        try {
            TestPdf.open(bytes)
            fail("expected PdfTextException")
        } catch (e: PdfTextException) {
            assertEquals("encrypted", e.message)
        }
        try {
            TestPdf.open(TestPdf.doc("(x) Tj").xrefStream(trailerExtra = "/Encrypt 99 0 R"))
            fail("expected PdfTextException")
        } catch (e: PdfTextException) {
            assertEquals("encrypted", e.message)
        }
    }

    @Test(timeout = 20000)
    fun garbageAndTruncatedFilesThrowWithoutHanging() {
        val rnd = java.util.Random(7)
        val junk = ByteArray(200_000).also { rnd.nextBytes(it) }
        expectFailure(junk)
        expectFailure(ByteArray(0))
        expectFailure("%PDF-1.4\n".toByteArray())
        expectFailure("%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\ntrailer << /Root 1 0 R >>".toByteArray())
        val good = TestPdf.doc("BT /F1 12 Tf 100 700 Td (x) Tj ET").classic()
        // truncated in the middle of the objects: must not hang; either opens or fails cleanly
        for (cut in intArrayOf(10, 40, 100, 200, 330, good.size / 2, good.size - 30)) {
            try {
                TestPdf.open(good.copyOf(cut)).use { it.page(0) }
            } catch (_: PdfTextException) {
                // fine
            }
        }
        // cyclic Pages tree and Prev loop
        val cyc = TestPdf()
        cyc.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
        cyc.put(2, "<< /Type /Pages /Kids [2 0 R 3 0 R] /Count 1 >>")
        cyc.put(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>")
        val r = TestPdf.open(cyc.classic())
        assertEquals(1, r.pageCount)
        assertEquals(PageGlyphs.EMPTY, r.page(0))
        val prevLoop = TestPdf.doc("(x) Tj").classic()
        val s = String(prevLoop, Charsets.ISO_8859_1)
        val off = s.indexOf("xref\n0 ")
        val loop = s.replace("/Size 6 /Root 1 0 R", "/Size 6 /Prev $off /Root 1 0 R").toByteArray(Charsets.ISO_8859_1)
        TestPdf.open(loop).close()
    }

    private fun expectFailure(bytes: ByteArray) {
        try {
            TestPdf.open(bytes).close()
            fail("expected PdfTextException")
        } catch (_: PdfTextException) {
            // expected
        }
    }

    @Test
    fun incrementalUpdateNewestObjectWins() {
        val base = TestPdf.doc("BT /F1 12 Tf 100 700 Td (Old) Tj ET").classic()
        val baseStr = String(base, Charsets.ISO_8859_1)
        val prevXref = baseStr.substringAfterLast("startxref\n").trim().substringBefore('\n').toInt()
        val out = ByteArrayOutputStream()
        out.write(base)
        val newOff = out.size()
        val content = "BT /F1 12 Tf 100 700 Td (New) Tj ET"
        out.write("4 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        val xrefOff = out.size()
        out.write(
            ("xref\n4 1\n${String.format("%010d", newOff)} 00000 n \ntrailer\n<< /Size 6 /Root 1 0 R /Prev $prevXref >>\n" +
                "startxref\n$xrefOff\n%%EOF\n").toByteArray(Charsets.ISO_8859_1),
        )
        assertEquals("New", textOf(out.toByteArray()))
    }

    @Test
    fun contentsArrayIsConcatenatedWithSpace() {
        val pdf = TestPdf.doc("")
        pdf.put(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents [4 0 R 6 0 R] /Resources << /Font << /F1 5 0 R >> >> >>")
        pdf.putStream(4, "", "BT /F1 12 Tf 100 700 Td (One) Tj 0 -20")
        pdf.putStream(6, "", "Td (Two) Tj ET")
        assertEquals("One\nTwo", textOf(pdf.classic()))
    }

    @Test
    fun inlineImageAndCommentsAreSkipped() {
        val bin = byteArrayOf(0, -1, 69, 73, 32, -2, 10, 69, 73, 0, 1, 2)
        val content = ByteArrayOutputStream()
        content.write("% a comment (with paren\nBT /F1 12 Tf 100 700 Td (Before) Tj ET q 10 0 0 10 0 0 cm BI /W 4 /H 3 /BPC 8 /CS /G ID ".toByteArray())
        content.write(bin)
        content.write("\nEI Q BT /F1 12 Tf 100 680 Td (After) Tj ET".toByteArray())
        val pdf = TestPdf.doc("")
        pdf.putStream(4, "", content.toByteArray())
        assertEquals("Before\nAfter", textOf(pdf.classic()))
    }

    @Test
    fun stringEscapesAndHexStrings() {
        val g = TestPdf.open(
            TestPdf.doc("BT /F1 12 Tf 10 700 Td (a\\(b\\)c (nested) \\101\\60\\\nZ) Tj <48 65 6C6C6F2> Tj ET").classic(),
        ).page(0)
        assertEquals("a(b)c (nested) A0ZHello", g.text)
    }

    @Test
    fun wordAndCharSpacingAndHorizontalScale() {
        val g = TestPdf.open(
            TestPdf.doc("BT /F1 10 Tf 2 Tc 5 Tw 50 Tz 0 100 Td (a b) Tj ET").classic(),
        ).page(0)
        assertEquals("a b", g.text)
        // glyph 'a': 0.5*10 = 5 wide * Th(.5) = 2.5 box; advance = (5 + 2) * .5 = 3.5
        assertEquals(2.5f, g.boxes[2] - g.boxes[0], eps)
        assertEquals(3.5f, g.boxes[4], eps)
        // space: advance (5 + 2 + 5) * .5 = 6
        assertEquals(9.5f, g.boxes[8], eps)
    }

    @Test
    fun fontSizeFromTmAndCtmIsUsedForBoxes() {
        val g = TestPdf.open(TestPdf.doc("2 0 0 2 0 0 cm BT /F1 5 Tf 1 0 0 1 20 50 Tm (Z) Tj ET").classic()).page(0)
        // size 5 * ctm 2 = 10, origin (40, 100): width 5, ascent 8
        assertBox(g, 0, 40f, 792f - 108f, 45f, 792f - 98f)
    }

    @Test
    fun textOutsidePageIsSkippedAndInvisibleTextKept() {
        val g = TestPdf.open(
            TestPdf.doc("BT /F1 12 Tf 3 Tr 1000 1000 Td (off) Tj 0 -300 Td (-9999) Tj ET BT /F1 12 Tf 3 Tr 50 50 Td (ocr) Tj ET").classic(),
        ).page(0)
        assertEquals("ocr", g.text)
    }

    @Test
    fun rotatedTextStillGetsBoxes() {
        val g = TestPdf.open(TestPdf.doc("BT /F1 10 Tf 0.7071 0.7071 -0.7071 0.7071 100 100 Tm (ab) Tj ET").classic()).page(0)
        assertEquals("ab", g.text)
        assertTrue(g.boxes[2] > g.boxes[0])
        assertTrue(g.boxes[3] > g.boxes[1])
        assertTrue(g.boxes[4] > g.boxes[0])
    }

    @Test
    fun emptyPageIsEmptyAndBadPageIndexIsSafe() {
        val r = TestPdf.open(TestPdf.doc("0 0 m 10 10 l S").classic())
        assertEquals(PageGlyphs.EMPTY, r.page(0))
        assertEquals(PageGlyphs.EMPTY, r.page(5))
        assertEquals(PageGlyphs.EMPTY, r.page(-1))
        assertEquals(0, PageGlyphs.EMPTY.lineStarts.size)
    }

    @Test
    fun multiplePagesAreCountedInOrder() {
        val pdf = TestPdf()
        pdf.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
        pdf.put(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 3 /MediaBox [0 0 200 200] /Resources << /Font << /F1 9 0 R >> >> >>")
        pdf.put(3, "<< /Type /Page /Parent 2 0 R /Contents 5 0 R >>")
        pdf.put(4, "<< /Type /Pages /Parent 2 0 R /Kids [6 0 R 7 0 R] /Count 2 /MediaBox [0 0 300 300] >>")
        pdf.putStream(5, "", "BT /F1 12 Tf 10 100 Td (one) Tj ET")
        pdf.put(6, "<< /Type /Page /Parent 4 0 R /Contents 8 0 R >>")
        pdf.put(7, "<< /Type /Page /Parent 4 0 R /Contents 10 0 R /MediaBox [0 0 50 60] >>")
        pdf.putStream(8, "", "BT /F1 12 Tf 10 100 Td (two) Tj ET")
        pdf.put(9, TestPdf.HELV_FONT)
        pdf.putStream(10, "", "BT /F1 12 Tf 10 10 Td (three) Tj ET")
        val r = TestPdf.open(pdf.classic())
        assertEquals(3, r.pageCount)
        assertEquals("one", r.page(0).text)
        assertEquals("two", r.page(1).text)
        assertEquals("three", r.page(2).text)
        assertArrayEquals(floatArrayOf(200f, 200f), r.pageSize(0), eps)
        assertArrayEquals(floatArrayOf(300f, 300f), r.pageSize(1), eps)
        assertArrayEquals(floatArrayOf(50f, 60f), r.pageSize(2), eps)
    }

    @Test
    fun indirectStreamLengthIsResolved() {
        val pdf = TestPdf.doc("")
        val content = "BT /F1 12 Tf 10 100 Td (Len) Tj ET"
        pdf.put(4, "<< /Length 6 0 R >>\nstream\n$content\nendstream")
        pdf.put(6, "${content.length}")
        assertEquals("Len", textOf(pdf.classic()))
        // a wrong Length falls back to scanning for endstream
        pdf.put(6, "3")
        assertEquals("Len", textOf(pdf.classic()))
    }

    @Test
    fun pageParsingIsFast() {
        val sb = StringBuilder("BT /F1 10 Tf 12 TL 40 760 Td ")
        for (i in 0 until 60) sb.append("(The quick brown fox jumps over the lazy dog, again and again and again.) Tj T* ")
        sb.append("ET")
        val r = TestPdf.open(TestPdf.doc(sb.toString()).classic())
        r.page(0)
        val t0 = System.nanoTime()
        repeat(20) { assertTrue(r.page(0).text.length > 3000) }
        val perPageMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("page took $perPageMs ms", perPageMs < 100.0)
    }
}
