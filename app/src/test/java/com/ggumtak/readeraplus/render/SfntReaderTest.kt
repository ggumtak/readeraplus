package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class SfntReaderTest {

    private fun parse(bytes: ByteArray): SfntInfo? = SfntReader.parse(ByteArraySfntSource(bytes))

    private fun font(vararg records: NameRecord, extra: SfntBuilder.() -> Unit = {}): ByteArray =
        SfntBuilder().table("name", NameTables.build(*records)).apply(extra).build()

    @Test
    fun readsEnglishFamilyName() {
        val info = parse(font(NameTables.win(1, "Test Serif"), NameTables.win(2, "Regular")))!!
        assertEquals("Test Serif", info.family)
        assertEquals("Regular", info.style)
        assertEquals("Test Serif", info.displayName)
        assertFalse(info.variable)
    }

    @Test
    fun prefersKoreanRecord() {
        val info = parse(
            font(
                NameTables.win(1, "Sample Myeongjo"),
                NameTables.win(1, "샘플 명조", language = 0x0412),
                NameTables.win(2, "Regular"),
            ),
        )!!
        assertEquals("샘플 명조", info.family)
        assertEquals("샘플 명조", info.displayName)
    }

    @Test
    fun prefersTypographicFamilyWithinSameLanguage() {
        val info = parse(
            font(
                NameTables.win(1, "Sample Sans SemiBold"),
                NameTables.win(2, "Regular"),
                NameTables.win(16, "Sample Sans"),
                NameTables.win(17, "SemiBold"),
            ),
        )!!
        assertEquals("Sample Sans", info.family)
        assertEquals("SemiBold", info.style)
        assertEquals("Sample Sans SemiBold", info.displayName)
    }

    @Test
    fun koreanNameId1BeatsEnglishTypographicFamily() {
        val info = parse(
            font(
                NameTables.win(16, "Sample"),
                NameTables.win(1, "샘플 바탕", language = 0x0412),
                NameTables.win(2, "Bold", language = 0x0412),
            ),
        )!!
        assertEquals("샘플 바탕", info.family)
        assertEquals("샘플 바탕 Bold", info.displayName)
    }

    @Test
    fun styleAlreadyInFamilyIsNotRepeated() {
        val info = parse(font(NameTables.win(1, "Sample Light"), NameTables.win(2, "Light")))!!
        assertEquals("Sample Light", info.displayName)
    }

    @Test
    fun koreanRegularStyleWordIsHidden() {
        val info = parse(font(NameTables.win(1, "샘플", language = 0x0412), NameTables.win(2, "보통", language = 0x0412)))!!
        assertEquals("샘플", info.displayName)
    }

    @Test
    fun macAndUnicodeRecordsAsFallback() {
        assertEquals("MacOnly", parse(font(NameTables.mac(1, "MacOnly")))!!.family)
        assertEquals("UniOnly", parse(font(NameTables.unicode(1, "UniOnly")))!!.family)
        // Windows beats Mac for the same language
        assertEquals("WinName", parse(font(NameTables.mac(1, "MacName"), NameTables.win(1, "WinName")))!!.family)
    }

    @Test
    fun controlCharsAndBlankNamesAreIgnored() {
        val info = parse(font(NameTables.win(1, "  \u0000 "), NameTables.mac(1, "Good\u0001Name")))!!
        assertEquals("GoodName", info.family)
    }

    @Test
    fun namelessFontStillParses() {
        val bytes = SfntBuilder().table("OS/2", NameTables.os2(400)).build()
        val info = parse(bytes)!!
        assertNull(info.family)
        assertNull(info.displayName)
        assertEquals(400, info.weightClass)
    }

    @Test
    fun detectsWeightAxis() {
        val bytes = font(NameTables.win(1, "Var")) {
            table("fvar", NameTables.fvar(Triple("wght", 100f, 400f to 900f), Triple("wdth", 75f, 100f to 100f)))
        }
        val info = parse(bytes)!!
        assertTrue(info.variable)
        assertEquals(100f, info.wghtMin, 0.001f)
        assertEquals(400f, info.wghtDefault, 0.001f)
        assertEquals(900f, info.wghtMax, 0.001f)
    }

    @Test
    fun weightAxisNotFirst() {
        val bytes = font(NameTables.win(1, "Var")) {
            table("fvar", NameTables.fvar(Triple("opsz", 8f, 12f to 72f), Triple("wght", 300f, 400f to 700f)))
        }
        assertTrue(parse(bytes)!!.variable)
    }

    @Test
    fun otherAxesOnlyIsNotVariableWeight() {
        val bytes = font(NameTables.win(1, "Opsz")) { table("fvar", NameTables.fvar(Triple("opsz", 8f, 12f to 72f))) }
        assertFalse(parse(bytes)!!.variable)
    }

    @Test
    fun readsOs2() {
        val bytes = font(NameTables.win(1, "X")) { table("OS/2", NameTables.os2(700, panoseFamily = 2, panoseSerif = 11, fsSelection = 0x21)) }
        val info = parse(bytes)!!
        assertEquals(700, info.weightClass)
        assertTrue(info.italic)
        assertEquals(true, info.sansHint)
        val serif = parse(font(NameTables.win(1, "Y")) { table("OS/2", NameTables.os2(400, 2, 3)) })!!
        assertEquals(false, serif.sansHint)
        assertFalse(serif.italic)
        val unknown = parse(font(NameTables.win(1, "Z")) { table("OS/2", NameTables.os2(400, 0, 0)) })!!
        assertNull(unknown.sansHint)
    }

    @Test
    fun acceptsCffSignature() {
        val bytes = SfntBuilder(SfntReader.TAG_OTTO).table("name", NameTables.build(NameTables.win(1, "Cff"))).build()
        assertEquals("Cff", parse(bytes)!!.family)
        assertTrue(SfntReader.looksLikeSfnt(bytes))
    }

    @Test
    fun readsFirstFaceOfCollection() {
        val a = font(NameTables.win(1, "First"))
        val b = font(NameTables.win(1, "Second"))
        val ttc = SfntBuilder.ttc(a, b)
        assertTrue(SfntReader.looksLikeSfnt(ttc))
        val info = parse(ttc)!!
        assertEquals("First", info.family)
        assertEquals(2, info.faceCount)
    }

    @Test
    fun rejectsNonFonts() {
        assertNull(parse(ByteArray(0)))
        assertNull(parse(ByteArray(11)))
        assertNull(parse("wOFF0000000000000000".toByteArray()))
        assertNull(parse("PK\u0003\u0004 not a font at all".toByteArray()))
        // zero tables
        assertNull(parse(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertFalse(SfntReader.looksLikeSfnt("wOF2".toByteArray()))
        assertFalse(SfntReader.looksLikeSfnt(byteArrayOf(0, 1)))
    }

    @Test
    fun truncatedDirectoryReturnsNull() {
        val full = font(NameTables.win(1, "Trunc"))
        assertNull(parse(full.copyOf(20)))
    }

    @Test
    fun tableOutsideFileIsSkipped() {
        val full = font(NameTables.win(1, "Short"))
        // Cut into the name table: the directory survives, the name table no longer fits.
        val info = parse(full.copyOf(full.size - 3))
        assertNotNull(info)
        assertNull(info!!.family)
    }

    @Test
    fun hostileNameOffsetsDoNotThrow() {
        val bytes = font(NameTables.win(1, "Ok"), NameTables.win(16, "Also"))
        // Corrupt the string offsets / lengths of the name records.
        val nameStart = findTable(bytes, "name")
        val c = bytes.copyOf()
        c[nameStart + 6 + 8] = 0x7F // length of record 0
        c[nameStart + 6 + 12 + 10] = 0x7F // offset of record 1
        parse(c) // must not throw
    }

    @Test
    fun fuzzNeverThrows() {
        val base = font(
            NameTables.win(1, "Fuzz"),
            NameTables.win(1, "퍼즈", language = 0x0412),
            NameTables.win(16, "Fuzz Family"),
            NameTables.win(17, "Bold"),
            NameTables.mac(1, "FuzzMac"),
        ) {
            table("fvar", NameTables.fvar(Triple("wght", 100f, 400f to 900f)))
            table("OS/2", NameTables.os2(500, 2, 11))
        }
        val rnd = Random(1234)
        repeat(3000) {
            val b = if (rnd.nextInt(4) == 0) base.copyOf(rnd.nextInt(base.size + 1)) else base.copyOf()
            val flips = 1 + rnd.nextInt(8)
            repeat(flips) { if (b.isNotEmpty()) b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            parse(b)
        }
        repeat(500) {
            val b = ByteArray(rnd.nextInt(300))
            rnd.nextBytes(b)
            if (b.size >= 4 && rnd.nextBoolean()) { b[0] = 0; b[1] = 1; b[2] = 0; b[3] = 0 }
            parse(b)
        }
    }

    @Test
    fun parsesFromFile() {
        val f = File.createTempFile("sfnt", ".ttf")
        try {
            f.writeBytes(font(NameTables.win(1, "FileFont")))
            assertEquals("FileFont", SfntReader.parse(f)!!.family)
            f.writeBytes("garbage".toByteArray())
            assertNull(SfntReader.parse(f))
        } finally {
            f.delete()
        }
        assertNull(SfntReader.parse(File("/nonexistent/dir/x.ttf")))
    }

    private fun findTable(font: ByteArray, tag: String): Int {
        val n = ((font[4].toInt() and 0xFF) shl 8) or (font[5].toInt() and 0xFF)
        for (i in 0 until n) {
            val p = 12 + i * 16
            val t = String(font, p, 4, Charsets.ISO_8859_1)
            if (t == tag) return SfntReader.u32(font, p + 8).toInt()
        }
        error("no $tag")
    }
}
