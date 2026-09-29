package com.ggumtak.readeraplus.format.txt

import com.ggumtak.readeraplus.format.ParseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.random.Random

class TxtCharsetsTest {
    private val sample = "제1화 시작\n똠방각하와 햏자는 화살을 쐈다. 뷁! 가나다 ABC 123\n“따옴표”와 …말줄임표\n"

    private fun decodeAll(dec: TxtDecoder, b: ByteArray, from: Int = 0): String {
        val out = CharArray(dec.maxChars(b.size - from))
        val n = dec.decode(b, from, b.size, out, 0)
        return String(out, 0, n)
    }

    private fun parsedText(b: ByteArray, o: ParseOptions = ParseOptions(txtDetectChapters = false)): Pair<String, String> {
        val p = TxtParser.parse(b, b.size, o)
        val text = TxtTestUtil.sections(p, o).joinToString("\n") { it.text }
        return p.decoder.name to text
    }

    @Test
    fun cp949DecodesUhcOnlySyllables() {
        val b = sample.toByteArray(TxtTestUtil.CP949)
        // 똠 / 햏 / 쐈 / 뷁 are not in plain EUC-KR (KS X 1001)
        val euc = java.nio.charset.Charset.forName("EUC-KR")
        val strictEuc = euc.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        var eucFailed = false
        try { strictEuc.decode(ByteBuffer.wrap(b)) } catch (_: Exception) { eucFailed = true }
        assertTrue("sample must need CP949 extensions", eucFailed)
        assertEquals(sample, decodeAll(TxtCharsets.cp949, b))
        val (name, text) = parsedText(b)
        assertEquals("MS949", name)
        assertTrue(text.contains("똠방각하와 햏자는 화살을 쐈다. 뷁!"))
    }

    @Test
    fun cp949TableMatchesPlatformForAllPairs() {
        val cs = TxtCharsets.cp949Charset!!
        val dec = TxtCharsets.cp949
        val jd = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        var checked = 0
        for (lead in 0x81..0xFE) for (trail in 0x41..0xFE) {
            val b = byteArrayOf(lead.toByte(), trail.toByte())
            val expected = try { jd.reset(); jd.decode(ByteBuffer.wrap(b)).toString() } catch (_: Exception) { null }
            if (expected == null || expected.length != 1 || expected == "�") continue
            assertEquals("pair %02X%02X".format(lead, trail), expected, decodeAll(dec, b))
            checked++
        }
        assertTrue("checked $checked pairs", checked > 17000)
    }

    @Test
    fun cp949MalformedNeverSwallowsNewline() {
        // lead byte followed by LF, a stray 0x80 / 0xFF, and a truncated lead at the end
        val b = byteArrayOf(0x41, 0xB0.toByte(), 0x0A, 0x42, 0x80.toByte(), 0x0A, 0xFF.toByte(), 0xB0.toByte(), 0xA1.toByte(), 0x0A, 0xC8.toByte())
        val s = decodeAll(TxtCharsets.cp949, b)
        assertEquals("A�\nB�\n�가\n�", s)
        assertEquals(3, s.count { it == '\n' })
    }

    @Test
    fun utf8WithAndWithoutBom() {
        val plain = sample.toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + plain
        for (b in listOf(plain, bom)) {
            val (name, text) = parsedText(b)
            assertEquals("UTF-8", name)
            assertTrue(text.startsWith("제1화 시작"))
            assertTrue(text.contains("똠방각하"))
        }
        assertEquals("UTF-8", TxtCharsets.sniffName(bom, 0, bom.size))
        assertEquals("UTF-8", TxtCharsets.sniffName(plain, 0, plain.size))
    }

    @Test
    fun utf8MalformedAndTruncated() {
        val b = byteArrayOf(0x61, 0xE3.toByte(), 0x81.toByte(), 0x0A, 0x62, 0xC0.toByte(), 0x0A, 0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte(), 0x0A, 0xEA.toByte(), 0xB0.toByte())
        val s = decodeAll(Utf8Decoder, b)
        assertEquals("a�\nb�\n😀\n�", s)
        // a truncated final sequence is tolerated by detection
        val ok = "가나다".toByteArray(Charsets.UTF_8) + byteArrayOf(0xEA.toByte(), 0xB0.toByte())
        assertTrue(Utf8Decoder.decodeDetect(ok, 0, ok.size, null, 0, truncatedOk = true) >= 0)
        // a mostly-UTF-8 file with one corrupt byte is still UTF-8
        val big = (TxtTestUtil.body(Random(1), 5000).toByteArray(Charsets.UTF_8)).also { it[100] = 0xFF.toByte() }
        assertEquals("UTF-8", TxtCharsets.sniffName(big, 0, big.size))
        // CP949 text is not UTF-8
        val cp = TxtTestUtil.body(Random(2), 3000).toByteArray(TxtTestUtil.CP949)
        assertEquals("MS949", TxtCharsets.sniffName(cp, 0, cp.size))
        assertEquals(Utf8Decoder.NOT_UTF8, Utf8Decoder.decodeDetect(cp, 0, cp.size, null, 0, true))
    }

    @Test
    fun utf16AllVariants() {
        val le = sample.toByteArray(Charsets.UTF_16LE)
        val be = sample.toByteArray(Charsets.UTF_16BE)
        val leBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + le
        val beBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + be
        for ((b, expected) in listOf(le to "UTF-16LE", be to "UTF-16BE", leBom to "UTF-16LE", beBom to "UTF-16BE")) {
            val (name, text) = parsedText(b)
            assertEquals(expected, name)
            assertTrue("$expected: $text", text.startsWith("제1화 시작\n똠방각하"))
            assertEquals(expected, TxtCharsets.sniffName(b, 0, b.size))
        }
        // Korean-heavy UTF-16 without BOM (few ASCII chars) is still recognised
        val text = TxtTestUtil.body(Random(3), 3000).replace("“", "").replace("”", "").replace(".", "")
        val korean = text.toByteArray(Charsets.UTF_16LE)
        assertSame(TxtCharsets.UTF16LE, TxtCharsets.utf16WithoutBom(korean, 0, korean.size))
        val koreanBe = text.toByteArray(Charsets.UTF_16BE)
        assertSame(TxtCharsets.UTF16BE, TxtCharsets.utf16WithoutBom(koreanBe, 0, koreanBe.size))
        // UTF-8 / CP949 never look like UTF-16
        val u8 = sample.toByteArray(Charsets.UTF_8)
        assertNull(TxtCharsets.utf16WithoutBom(u8, 0, u8.size))
    }

    @Test
    fun utf16UnpairedSurrogatesAndOddByte() {
        val b = byteArrayOf(0x3D, 0xD8.toByte(), 0x0A, 0x00, 0x41, 0x00, 0x42)
        assertEquals("�\nA�", decodeAll(TxtCharsets.UTF16LE, b))
    }

    @Test
    fun forcedEncodings() {
        val b = sample.toByteArray(TxtTestUtil.CP949)
        for (name in listOf("MS949", "EUC-KR", "cp949", "x-windows-949")) {
            val (dname, text) = parsedText(b, ParseOptions(txtDetectChapters = false, txtEncoding = name))
            assertEquals("MS949", dname)
            assertTrue(text.contains("쐈다"))
        }
        assertNotNull(TxtCharsets.forName("UTF-16LE"))
        assertNull(TxtCharsets.forName("no-such-charset"))
        // unknown forced encoding falls back to detection
        val (dname, _) = parsedText(b, ParseOptions(txtDetectChapters = false, txtEncoding = "bogus"))
        assertEquals("MS949", dname)
        // a generic java.nio charset works too
        val sj = "テスト\n日本語".toByteArray(charset("Shift_JIS"))
        val (n2, t2) = parsedText(sj, ParseOptions(txtDetectChapters = false, txtEncoding = "Shift_JIS"))
        assertEquals("Shift_JIS", n2)
        assertEquals("テスト\n日本語", t2)
        for (e in TxtDocuments.ENCODINGS) assertNotNull(e, TxtCharsets.forName(e))
    }

    @Test
    fun advanceMatchesEncodedLengths() {
        val r = Random(7)
        val text = TxtTestUtil.body(r, 4000) + "😀 끝 end"
        val cases = listOf(
            Utf8Decoder to text.toByteArray(Charsets.UTF_8),
            TxtCharsets.cp949 to text.replace("😀", "?").toByteArray(TxtTestUtil.CP949),
            TxtCharsets.UTF16LE to text.toByteArray(Charsets.UTF_16LE),
            TxtCharsets.UTF16BE to text.toByteArray(Charsets.UTF_16BE),
        )
        for ((dec, b) in cases) {
            val s = decodeAll(dec, b)
            for (k in listOf(0, 1, 17, 500, 1234, s.length - 3, s.length)) {
                if (k in 1 until s.length && Character.isLowSurrogate(s[k])) continue
                val pos = dec.advance(b, 0, b.size, k)
                assertEquals("${dec.name} k=$k", s.substring(k), decodeAll(dec, b, pos))
            }
        }
    }
}
