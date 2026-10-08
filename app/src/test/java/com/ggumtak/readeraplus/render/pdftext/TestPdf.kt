package com.ggumtak.readeraplus.render.pdftext

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TreeMap
import java.util.zip.Deflater

/** Test-only PDF writer: collects objects and emits a classic-xref or xref-stream file with correct offsets. */
class TestPdf {
    private val objs = TreeMap<Int, ByteArray>()
    private val streamNums = HashSet<Int>()

    fun put(num: Int, body: String): TestPdf {
        objs[num] = body.toByteArray(Charsets.ISO_8859_1)
        return this
    }

    /** A stream object; [dict] holds extra dictionary entries (without Length). [flate] compresses [data]. */
    fun putStream(num: Int, dict: String, data: ByteArray, flate: Boolean = false): TestPdf {
        val payload = if (flate) deflate(data) else data
        val head = "<< /Length ${payload.size} ${if (flate) "/Filter /FlateDecode " else ""}$dict >>\nstream\n"
        val b = ByteArrayOutputStream()
        b.write(head.toByteArray(Charsets.ISO_8859_1))
        b.write(payload)
        b.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        objs[num] = b.toByteArray()
        streamNums.add(num)
        return this
    }

    fun putStream(num: Int, dict: String, text: String, flate: Boolean = false): TestPdf =
        putStream(num, dict, text.toByteArray(Charsets.ISO_8859_1), flate)

    private fun header(out: ByteArrayOutputStream) {
        out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
    }

    /** Classic xref table. [offsetShift] corrupts every offset (to test recovery). */
    fun classic(root: Int = 1, trailerExtra: String = "", offsetShift: Int = 0, startxrefOk: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        header(out)
        val offsets = HashMap<Int, Int>()
        for ((n, body) in objs) {
            offsets[n] = out.size()
            out.write("$n 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val size = (objs.lastKey()) + 1
        val xref = out.size()
        val sb = StringBuilder("xref\n0 $size\n")
        sb.append("0000000000 65535 f \n")
        for (n in 1 until size) {
            val o = offsets[n]
            if (o == null) sb.append("0000000000 00000 f \n") else sb.append(String.format("%010d 00000 n \n", o + offsetShift))
        }
        sb.append("trailer\n<< /Size $size /Root $root 0 R $trailerExtra >>\nstartxref\n")
        sb.append(if (startxrefOk) xref else xref + 5)
        sb.append("\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** Cross-reference stream (with PNG-Up predictor), optionally packing non-stream objects into an object stream. */
    fun xrefStream(root: Int = 1, useObjStm: Boolean = true, trailerExtra: String = "", withStartxref: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        header(out)
        val nums = objs.keys.toList()
        val packed = if (useObjStm) nums.filter { it !in streamNums } else emptyList()
        val objStmNum = nums.last() + 1
        val xrefNum = objStmNum + 1
        val size = xrefNum + 1
        val type = IntArray(size)
        val f2 = IntArray(size)
        val f3 = IntArray(size)
        for (n in nums) {
            if (n in packed) {
                type[n] = 2
                f2[n] = objStmNum
                f3[n] = packed.indexOf(n)
            } else {
                type[n] = 1
                f2[n] = out.size()
                out.write("$n 0 obj\n".toByteArray(Charsets.ISO_8859_1))
                out.write(objs[n]!!)
                out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            }
        }
        if (packed.isNotEmpty()) {
            val head = StringBuilder()
            val body = ByteArrayOutputStream()
            for (n in packed) {
                head.append(n).append(' ').append(body.size()).append(' ')
                body.write(objs[n]!!)
                body.write('\n'.code)
            }
            val data = head.toString().toByteArray(Charsets.ISO_8859_1) + body.toByteArray()
            val payload = deflate(data)
            type[objStmNum] = 1
            f2[objStmNum] = out.size()
            out.write("$objStmNum 0 obj\n<< /Type /ObjStm /N ${packed.size} /First ${head.length} /Filter /FlateDecode /Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
            out.write(payload)
            out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xrefOff = out.size()
        type[xrefNum] = 1
        f2[xrefNum] = xrefOff
        type[0] = 0
        f3[0] = 65535
        val cols = 7
        val raw = ByteArrayOutputStream()
        var prev = ByteArray(cols)
        for (n in 0 until size) {
            val row = byteArrayOf(
                type[n].toByte(),
                (f2[n] ushr 24).toByte(), (f2[n] ushr 16).toByte(), (f2[n] ushr 8).toByte(), f2[n].toByte(),
                (f3[n] ushr 8).toByte(), f3[n].toByte(),
            )
            raw.write(2)
            for (i in 0 until cols) raw.write(row[i] - prev[i])
            prev = row
        }
        val payload = deflate(raw.toByteArray())
        out.write(
            ("$xrefNum 0 obj\n<< /Type /XRef /Size $size /W [1 4 2] /Root $root 0 R $trailerExtra " +
                "/Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns $cols >> /Length ${payload.size} >>\nstream\n")
                .toByteArray(Charsets.ISO_8859_1),
        )
        out.write(payload)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        if (withStartxref) out.write("startxref\n$xrefOff\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    companion object {
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater()
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        /** 95 widths of 500 (0.5 em) for codes 32..126, so box positions are easy to predict. */
        val HALF_WIDTHS = (1..95).joinToString(" ") { "500" }

        val HELV_FONT =
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding " +
                "/FirstChar 32 /LastChar 126 /Widths [$HALF_WIDTHS] >>"

        /**
         * Catalog (1), Pages (2), Page (3), Contents (4) and a half-width Helvetica font (5, as /F1). [pageExtra] is
         * appended to the page dictionary, [resExtra] to its /Resources.
         */
        fun doc(content: String, mediaBox: String = "[0 0 612 792]", pageExtra: String = "", resExtra: String = "", font: String = HELV_FONT): TestPdf {
            val p = TestPdf()
            p.put(1, "<< /Type /Catalog /Pages 2 0 R >>")
            p.put(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            p.put(3, "<< /Type /Page /Parent 2 0 R /MediaBox $mediaBox /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> $resExtra >> $pageExtra >>")
            p.putStream(4, "", content)
            p.put(5, font)
            return p
        }

        fun write(bytes: ByteArray): File {
            val f = File.createTempFile("pdftext", ".pdf")
            f.deleteOnExit()
            f.writeBytes(bytes)
            return f
        }

        fun open(bytes: ByteArray): PdfTextReader = PdfTextReader.open(write(bytes))
    }
}
