package com.ggumtak.readeraplus.render.pdftext

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextRobustnessTest {
    private val iso = Charsets.ISO_8859_1

    @Test
    fun hybridXRefStmProvidesObjectsMarkedFreeInTheTable() {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(iso))
        val off = IntArray(8)
        w("%PDF-1.5\n")
        fun obj(n: Int, body: String) {
            off[n] = out.size()
            w("$n 0 obj\n$body\nendobj\n")
        }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        obj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
        val content = "BT /F1 12 Tf 10 100 Td (Hybrid) Tj ET"
        obj(4, "<< /Length ${content.length} >>\nstream\n$content\nendstream")
        obj(5, TestPdf.HELV_FONT)
        off[6] = out.size()
        w("6 0 obj\n<< /Type /XRef /Size 7 /W [1 4 2] /Index [4 1] /Length 7 >>\nstream\n")
        val o4 = off[4]
        out.write(byteArrayOf(1, (o4 ushr 24).toByte(), (o4 ushr 16).toByte(), (o4 ushr 8).toByte(), o4.toByte(), 0, 0))
        w("\nendstream\nendobj\n")
        val xref = out.size()
        val sb = StringBuilder("xref\n0 7\n0000000000 65535 f \n")
        for (n in 1..6) {
            if (n == 4) sb.append("0000000000 00000 f \n") else sb.append(String.format("%010d 00000 n \n", off[n]))
        }
        sb.append("trailer\n<< /Size 7 /Root 1 0 R /XRefStm ${off[6]} >>\nstartxref\n$xref\n%%EOF\n")
        w(sb.toString())
        val r = TestPdf.open(out.toByteArray())
        assertEquals("Hybrid", r.page(0).text)
    }

    @Test
    fun junkBeforeHeaderShiftsOffsets() {
        val pdf = TestPdf.doc("BT /F1 12 Tf 100 700 Td (Shifted) Tj ET").classic()
        val junk = "GARBAGE-BEFORE-HEADER\r\n".toByteArray()
        assertEquals("Shifted", TestPdf.open(junk + pdf).page(0).text)
    }

    @Test
    fun type3FontUsesFontMatrixWidths() {
        val font = "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 600 700] /FontMatrix [0.001 0 0 0.001 0 0] " +
            "/CharProcs << /a 6 0 R >> /Encoding << /Type /Encoding /Differences [97 /a /b] >> /FirstChar 97 /LastChar 98 /Widths [600 300] >>"
        val pdf = TestPdf.doc("BT /F1 10 Tf 10 100 Td (ab) Tj (a) Tj ET", font = font)
        pdf.putStream(6, "", "600 0 d0 0 0 600 700 re f")
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("aba", g.text)
        assertEquals(6f, g.boxes[2] - g.boxes[0], 0.01f)
        assertEquals(3f, g.boxes[6] - g.boxes[4], 0.01f)
        assertEquals(19f, g.boxes[8], 0.01f)
    }

    @Test
    fun verticalWritingAdvancesDownward() {
        val pdf = TestPdf.doc("BT /F1 10 Tf 100 700 Td <0041 0042> Tj ET")
        pdf.put(5, "<< /Type /Font /Subtype /Type0 /BaseFont /Foo /Encoding /Identity-V /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>")
        pdf.put(6, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Foo /DW 1000 >>")
        pdf.putStream(7, "", "begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange 2 beginbfchar <0041> <0041> <0042> <0042> endbfchar endcmap")
        val g = TestPdf.open(pdf.classic()).page(0)
        assertEquals("A\nB", g.text)
        // second glyph sits one em lower
        assertEquals(10f, g.boxes[2 * 4 + 1] - g.boxes[1], 0.01f)
    }

    @Test
    fun malformedContentNeverThrows() {
        val bad = listOf(
            "BT /F1 12 Tf (unterminated string Tj",
            "BT /F1 12 Tf [ (a) 1 2 3 ] TJ ET ET ET Q Q Q q q q",
            "1 2 3 4 5 6 7 8 9 cm Tf Tj TJ ' \" Td Tm /F1 Do",
            "<< /A << /B [ [ [ >> >> BT (x) Tj",
            "BT /F1 1e9 Tf 1e300 0 0 1e300 0 0 Tm (x) Tj ET",
            "BT /F1 12 Tf NaN Td (x) Tj ET",
            "BI /W 1 ID",
            "BT /F1 0 Tf (x) Tj ET",
            "/F1 12 Tf (no BT) Tj",
        )
        for (c in bad) {
            val r = TestPdf.open(TestPdf.doc(c).classic())
            r.page(0) // must not throw
            r.close()
        }
    }

    @Test(timeout = 120000)
    fun mutatedFilesNeverHangOrThrowUnexpectedly() {
        val sources = listOf(
            TestPdf.doc("BT /F1 12 Tf 14 TL 100 700 Td (Hello World) Tj T* [(A) -300 (B)] TJ ET").classic(),
            TestPdf.doc("BT /F1 12 Tf 100 700 Td (Compressed) Tj ET").xrefStream(),
        )
        val rnd = Random(42)
        var opened = 0
        for (src in sources) {
            repeat(250) {
                val b = src.copyOf()
                var data = b
                when (rnd.nextInt(3)) {
                    0 -> repeat(1 + rnd.nextInt(10)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
                    1 -> data = b.copyOf(rnd.nextInt(b.size))
                    else -> {
                        val s = rnd.nextInt(b.size)
                        val l = minOf(rnd.nextInt(200), b.size - s)
                        data = b.copyOfRange(0, s) + b.copyOfRange(s + l, b.size)
                    }
                }
                try {
                    TestPdf.open(data).use { r ->
                        for (p in 0 until minOf(r.pageCount, 5)) r.page(p)
                        opened++
                    }
                } catch (_: PdfTextException) {
                    // clean failure
                }
            }
        }
        assertTrue("some mutations should still open", opened > 50)
    }

    @Test
    fun largePageTreeOpensQuickly() {
        val n = 3000
        val pdf = TestPdf()
        pdf.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
        val kids = (0 until n).joinToString(" ") { "${10 + it * 2} 0 R" }
        pdf.put(2, "<< /Type /Pages /Kids [$kids] /Count $n /MediaBox [0 0 300 400] /Resources << /Font << /F1 5 0 R >> >> >>")
        pdf.put(5, TestPdf.HELV_FONT)
        for (i in 0 until n) {
            pdf.put(10 + i * 2, "<< /Type /Page /Parent 2 0 R /Contents ${11 + i * 2} 0 R >>")
            pdf.putStream(11 + i * 2, "", "BT /F1 12 Tf 10 100 Td (Page $i) Tj ET")
        }
        val t0 = System.nanoTime()
        val r = TestPdf.open(pdf.classic())
        val openMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(n, r.pageCount)
        assertEquals("Page 2999", r.page(2999).text)
        assertEquals("Page 0", r.page(0).text)
        assertTrue("open took $openMs ms", openMs < 3000)
    }
}
