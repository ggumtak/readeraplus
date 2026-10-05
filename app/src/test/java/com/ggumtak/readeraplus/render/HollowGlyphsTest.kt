package com.ggumtak.readeraplus.render

import com.ggumtak.readeraplus.render.GlyphFixtures.DRAWN
import com.ggumtak.readeraplus.render.GlyphFixtures.ENDCHAR
import com.ggumtak.readeraplus.render.GlyphFixtures.WIDTH_ENDCHAR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * [HollowGlyphs]: characters a font maps to glyphs without an outline are found (TrueType and CFF), the ones blank by
 * design or substituted by a default GSUB feature keep their mapping, and the repaired cmap keeps every other mapping.
 * The bundled fonts are checked too (user report 2026-10-05: 나눔명조 OTF drew 聖 / 俗 as gaps).
 */
class HollowGlyphsTest {

    private fun scan(bytes: ByteArray): HollowGlyphs.Scan? = HollowGlyphs.scan(ByteArraySfntSource(bytes))

    private fun repaired(bytes: ByteArray): ByteArray {
        val scan = scan(bytes)!!
        val f = File.createTempFile("hollow", ".font")
        try {
            f.writeBytes(bytes)
            RandomAccessFile(f, "rw").use { HollowGlyphs.patch(it, scan, HollowGlyphs.cmapWithout(scan)) }
            return f.readBytes()
        } finally {
            f.delete()
        }
    }

    private fun mappings(s: HollowGlyphs.Scan): Map<Int, Int> = s.codePoints.indices.associate { s.codePoints[it] to s.glyphs[it] }

    /** Glyphs: 0 .notdef (outline), 1 A (outline), 2 blank, 3 no contours, 4 blank. */
    private val ttGlyphs = listOf(GlyphFixtures.dot(), GlyphFixtures.dot(), ByteArray(0), GlyphFixtures.noContours(), ByteArray(0))
    private val ttMap = mapOf(
        'A'.code to 1, 0x8056 to 2, 0x4FD7 to 3, 0xAC05 to 4, 0x3000 to 2, 0x3164 to 4, 0x20 to 4, 0x1F600 to 1,
    )

    @Test
    fun trueTypeBlankGlyphsAreFound() {
        val s = scan(GlyphFixtures.trueType(ttGlyphs, ttMap))!!
        // 聖 (empty glyf entry), 俗 (a header without contours) and 갅; not the spaces or the Hangul filler.
        assertArrayEquals(intArrayOf(0x4FD7, 0x8056, 0xAC05), s.hollow)
        assertEquals(ttMap, mappings(s))
    }

    @Test
    fun cffBlankCharStringsAreFound() {
        val font = GlyphFixtures.openType(
            listOf(ENDCHAR, DRAWN, WIDTH_ENDCHAR, ENDCHAR, byteArrayOf(139.toByte(), 139.toByte(), 139.toByte(), 139.toByte(), 14)),
            mapOf('A'.code to 1, 0x8056 to 2, 0x4FD7 to 3, 0xC2DC to 4, 0x3000 to 3),
        )
        val s = scan(font)!!
        // 950 endchar and endchar draw nothing; four operands before endchar are an accented character (seac).
        assertArrayEquals(intArrayOf(0x4FD7, 0x8056), s.hollow)
    }

    @Test
    fun blankCharStrings() {
        assertTrue(HollowGlyphs.isBlankCharString(ENDCHAR, 1))
        assertTrue(HollowGlyphs.isBlankCharString(WIDTH_ENDCHAR, WIDTH_ENDCHAR.size))
        assertTrue(HollowGlyphs.isBlankCharString(byteArrayOf(255.toByte(), 0, 1, 0, 0, 14), 6)) // a 16.16 width
        assertFalse(HollowGlyphs.isBlankCharString(DRAWN, DRAWN.size))
        assertFalse(HollowGlyphs.isBlankCharString(byteArrayOf(139.toByte(), 10), 2)) // callsubr: the subroutine draws
        assertFalse(HollowGlyphs.isBlankCharString(byteArrayOf(139.toByte(), 139.toByte(), 14), 3)) // 2 operands
        assertFalse(HollowGlyphs.isBlankCharString(byteArrayOf(139.toByte()), 1)) // no operator: malformed, left alone
    }

    @Test
    fun repairedCmapDropsOnlyTheBlankMappings() {
        val font = GlyphFixtures.trueType(ttGlyphs, ttMap)
        val fixed = repaired(font)
        val s = scan(fixed)!!
        assertEquals(0, s.hollow.size)
        assertEquals(ttMap - setOf(0x8056, 0x4FD7, 0xAC05), mappings(s))
        // Everything before the new cmap is the original file but the cmap record.
        val cmapRecord = 12 + 16 * 0 // 'cmap' sorts first
        for (i in font.indices) if (i !in cmapRecord + 4 until cmapRecord + 16) assertEquals("byte $i", font[i], fixed[i])
        // The record's checksum is the table's.
        val off = SfntReader.u32(fixed, cmapRecord + 8).toInt()
        val len = SfntReader.u32(fixed, cmapRecord + 12).toInt()
        assertEquals(0, off % 4)
        assertEquals(SfntReader.u32(fixed, cmapRecord + 4), HollowGlyphs.checksum(fixed.copyOfRange(off, off + len)))
        // The other readers still see the font.
        assertNotNull(SfntReader.parse(ByteArraySfntSource(fixed)))
    }

    @Test
    fun repairedCmapHasFormat4AndFormat12AndKeepsVariationSequences() {
        val glyphs = listOf(GlyphFixtures.dot(), GlyphFixtures.dot(), ByteArray(0))
        val map = mapOf('A'.code to 1, 'B'.code to 1, 0x8056 to 2, 0x20000 to 1)
        val font = SfntBuilder()
            .table("cmap", GlyphFixtures.cmap(map, GlyphFixtures.uvs()))
            .table("head", GlyphFixtures.head())
            .table("maxp", GlyphFixtures.maxp(glyphs.size))
            .table("loca", GlyphFixtures.glyfLoca(glyphs).second)
            .table("glyf", GlyphFixtures.glyfLoca(glyphs).first)
            .build()
        val s = scan(font)!!
        assertArrayEquals(intArrayOf(0x8056), s.hollow)
        assertNotNull(s.variations)
        val cmap = HollowGlyphs.cmapWithout(s)
        val records = (0 until SfntReader.u16(cmap, 2)).map { SfntReader.u16(cmap, 4 + 8 * it) to SfntReader.u16(cmap, 6 + 8 * it) }
        assertEquals(listOf(0 to 5, 3 to 1, 3 to 10), records)
        val uvsAt = SfntReader.u32(cmap, 8).toInt()
        assertArrayEquals(s.variations, cmap.copyOfRange(uvsAt, uvsAt + s.variations!!.size))
        assertEquals(map - 0x8056, mappings(scan(repaired(font))!!))
    }

    @Test
    fun aBmpTooScatteredForFormat4KeepsFormat12Only() {
        // 9000 single-character segments (every other code point, glyphs out of step) need more than 64 KB in format 4.
        val map = (0 until 9000).associate { 0x4E00 + 2 * it to 1 + (it % 2) }
        val font = GlyphFixtures.trueType(listOf(GlyphFixtures.dot(), GlyphFixtures.dot(), GlyphFixtures.dot(), ByteArray(0)), map + (0xAC05 to 3))
        val s = scan(font)!!
        assertArrayEquals(intArrayOf(0xAC05), s.hollow)
        val cmap = HollowGlyphs.cmapWithout(s)
        assertEquals(1, SfntReader.u16(cmap, 2))
        assertEquals(3, SfntReader.u16(cmap, 4))
        assertEquals(10, SfntReader.u16(cmap, 6))
        assertEquals(map, mappings(scan(repaired(font))!!))
    }

    @Test
    fun aDefaultSubstitutionKeepsItsInput() {
        // Jamo composition or a ligature may start from a placeholder glyph: a default feature's input stays mapped.
        val map = mapOf('A'.code to 1, 0x8056 to 2, 0xAC05 to 4)
        val ccmp = GlyphFixtures.trueType(ttGlyphs, map) { table("GSUB", GlyphFixtures.gsub("ccmp", listOf(4))) }
        assertArrayEquals(intArrayOf(0x8056), scan(ccmp)!!.hollow)
        // An opt-in feature (alternates, widths) doesn't.
        for (tag in listOf("aalt", "pwid", "ss01", "vert")) {
            val optIn = GlyphFixtures.trueType(ttGlyphs, map) { table("GSUB", GlyphFixtures.gsub(tag, listOf(2, 4))) }
            assertArrayEquals(tag, intArrayOf(0x8056, 0xAC05), scan(optIn)!!.hollow)
        }
    }

    @Test
    fun aGsubThatRepeatsItselfEndsTheScan() {
        // 200 features × 65,535 lookup indices: past the work limit the font is left alone, quickly.
        val font = GlyphFixtures.trueType(ttGlyphs, ttMap) { table("GSUB", GlyphFixtures.gsubSharingOneHugeFeature(200)) }
        val started = System.nanoTime()
        assertNull(scan(font))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        // A few of them are read through.
        val small = GlyphFixtures.trueType(ttGlyphs, ttMap) { table("GSUB", GlyphFixtures.gsubSharingOneHugeFeature(3)) }
        assertNotNull(scan(small))
    }

    @Test
    fun colourAndBitmapFacesAreLeftAlone() {
        for (tag in listOf("COLR", "sbix", "CBDT", "SVG ", "EBDT")) {
            assertNull(tag, scan(GlyphFixtures.trueType(ttGlyphs, ttMap) { table(tag, ByteArray(8)) }))
        }
    }

    @Test
    fun brokenInputIsNotAFont() {
        assertNull(scan(ByteArray(0)))
        assertNull(scan(ByteArray(64)))
        val font = GlyphFixtures.trueType(ttGlyphs, ttMap)
        for (cut in listOf(12, 40, font.size / 2, font.size - 3)) scan(font.copyOf(cut)) // never throws
        val noCmap = SfntBuilder().table("head", GlyphFixtures.head()).build()
        assertNull(scan(noCmap))
    }

    @Test
    fun collectionsRepairTheFirstFaceOnly() {
        val a = GlyphFixtures.trueType(ttGlyphs, ttMap)
        val b = GlyphFixtures.trueType(ttGlyphs, mapOf('A'.code to 1))
        val ttc = SfntBuilder.ttc(a, b)
        val s = scan(ttc)!!
        assertEquals(12L + 4 * 2, s.faceOffset)
        assertArrayEquals(intArrayOf(0x4FD7, 0x8056, 0xAC05), s.hollow)
        val fixed = repaired(ttc)
        assertEquals(0, scan(fixed)!!.hollow.size)
        // The second face's directory is untouched.
        val second = SfntReader.u32(fixed, 16).toInt()
        assertArrayEquals(ttc.copyOfRange(second, second + 12 + 16 * 5), fixed.copyOfRange(second, second + 12 + 16 * 5))
    }

    @Test
    fun charactersBlankByDesign() {
        for (c in intArrayOf(0x20, 0xA0, 0x3000, 0x09, 0xAD, 0x200B, 0x200D, 0xFEFF, 0xFE0F, 0x0301, 0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0xE000)) {
            assertFalse(Integer.toHexString(c), HollowGlyphs.needsInk(c))
        }
        for (c in "聖俗漢字똠됬갅가A€‐∥、。".codePoints().toArray() + 0x20000) {
            assertTrue(Integer.toHexString(c), HollowGlyphs.needsInk(c))
        }
    }

    @Test
    fun cffDictOperands() {
        val top = byteArrayOf(29, 0, 0, 0x30, 0x39, 17, 139.toByte(), 12, 6, 30, 0x1F, 18)
        assertEquals(0x3039L, HollowGlyphs.dictValue(top, 17))
        assertEquals(0L, HollowGlyphs.dictValue(top, 0x0C06))
        assertEquals(0L, HollowGlyphs.dictValue(top, 18)) // a real number reads as 0
        assertNull(HollowGlyphs.dictValue(top, 15))
        assertEquals(-1131L, HollowGlyphs.dictValue(byteArrayOf(254.toByte(), 255.toByte(), 17), 17))
        assertEquals(1131L, HollowGlyphs.dictValue(byteArrayOf(250.toByte(), 255.toByte(), 17), 17))
        assertNull(HollowGlyphs.dictValue(byteArrayOf(28, 1), 17))
    }

    @Test
    fun checksumOfPaddedWords() {
        assertEquals(0x01020304L, HollowGlyphs.checksum(byteArrayOf(1, 2, 3, 4)))
        assertEquals(0x01020304L + 0x05000000L, HollowGlyphs.checksum(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(0xFFFFFFFEL, HollowGlyphs.checksum(byteArrayOf(-1, -1, -1, -1, -1, -1, -1, -1)))
    }

    @Test
    fun aFontInsideAnotherFileReadsLikeTheFile() {
        // An uncompressed APK asset: the font's bytes from AssetFileDescriptor.startOffset.
        val font = GlyphFixtures.trueType(ttGlyphs, ttMap)
        val f = File.createTempFile("apk", ".bin")
        try {
            f.writeBytes(ByteArray(1001) { 7 } + font + ByteArray(50))
            java.io.FileInputStream(f).use { input ->
                val src = ChannelSfntSource(input.channel, 1001, font.size.toLong())
                assertArrayEquals(intArrayOf(0x4FD7, 0x8056, 0xAC05), HollowGlyphs.scan(src)!!.hollow)
                assertFalse(src.read(font.size - 2L, ByteArray(4), 0, 4))
            }
        } finally {
            f.delete()
        }
    }

    // ------------------------------------------------------------------ the bundled fonts

    private val assets: File? = listOf("app/src/main/assets", "src/main/assets").map(::File).firstOrNull { it.isDirectory }

    private fun bundled(path: String): HollowGlyphs.Scan {
        val f = File(assets, path)
        return FileSfntSource(f).use { HollowGlyphs.scan(it) } ?: throw AssertionError("$path: not scanned")
    }

    private fun isHan(c: Int) = c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF || c in 0xF900..0xFAFF || c in 0x20000..0x3FFFF

    @Test
    fun nanumMyeongjoLeavesHanjaToTheSystemFont() {
        assumeTrue("assets not found from ${File("").absolutePath}", assets != null)
        val b = FontCatalog.BUNDLED.first { it.id == FontCatalog.DEFAULT_ID }
        for (path in listOfNotNull(b.regular, b.bold)) {
            val s = bundled(path)
            // No Hanja in the cmap at all (MaruViewer's file): 聖 and 俗 come from the system's fallback.
            assertEquals(path, 0, s.codePoints.count(::isHan))
            assertEquals(path, 0, s.hollow.size)
            val m = mappings(s)
            for (c in "가갅똠、。(".codePoints().toArray()) assertTrue("$path ${Integer.toHexString(c)}", c in m)
        }
    }

    @Test
    fun noBundledFontDrawsHanjaOrIdeographicPunctuationBlank() {
        // Guard from the 2026-10-05 report: a font that maps 聖 / 、 to an empty glyph hides the system fallback.
        assumeTrue(assets != null)
        for (b in FontCatalog.BUNDLED) for (path in listOfNotNull(b.regular, b.bold)) {
            val s = bundled(path)
            val bad = s.hollow.filter { isHan(it) || it == 0x3001 || it == 0x3002 }
            assertEquals("$path: ${bad.take(5).map(Integer::toHexString)}", 0, bad.size)
        }
    }

    @Test
    fun bundledFontsWithBlankHangulAreRepaired() {
        assumeTrue(assets != null)
        // How many characters each file maps to a glyph without an outline (checked with fontTools), and one of them.
        val expected = mapOf(
            "fonts/MaruBuri-Regular.otf" to (6806 to 0xAC05), // 갅
            "fonts/SUIT-Regular.otf" to (8504 to 0xD3B2), // 펲
            "fonts/HakgyoansimBareonbatangR.otf" to (8822 to 0xB620), // 똠
            "fonts/HakgyoansimBareonbatangB.otf" to (8822 to 0xB42C), // 됬
            "fonts/NanumBarunGothic.otf" to (2 to 0x2225), // ∥
            "fonts/IropkeBatangM.otf" to (1 to 0x20AC), // €
            "fonts/RIDIBatang.otf" to (0 to 0),
            "fonts/Pretendard-Regular.otf" to (0 to 0),
        )
        for ((path, e) in expected) {
            val s = bundled(path)
            assertEquals(path, e.first, s.hollow.size)
            if (e.first > 0) assertTrue(path, e.second in s.hollow)
            assertFalse(path, 0xAC00 in s.hollow) // 가
        }
        val bytes = File(assets, "fonts/HakgyoansimBareonbatangR.otf").readBytes()
        val before = scan(bytes)!!
        val after = scan(repaired(bytes))!!
        assertEquals(0, after.hollow.size)
        assertEquals(before.codePoints.size - before.hollow.size, after.codePoints.size)
        assertEquals(mappings(before) - before.hollow.toSet(), mappings(after))
    }

    @Test
    fun serifFacesFallBackToTheSystemSerif() {
        assertEquals("serif", FontMath.systemFallback(true))
        assertEquals("sans-serif", FontMath.systemFallback(false))
        assertEquals(FontMath.SANS_FALLBACK, FontMath.systemFallback(false))
        // 나눔명조 (the default) and the other 명조 / 바탕 faces get the serif chain; the sans faces the default one.
        val serif = FontCatalog.BUNDLED.filter { it.serif }.map { it.id }.toSet()
        assertEquals(setOf("ridibatang", "nanummyeongjo", "maruburi", "iropkebatang", "bareonbatang"), serif)
    }

    @Test
    fun repairNamesFollowTheFileAndTheRules() {
        val asset = RepairNames.sourceKey(true, "fonts/SUIT-Regular.otf")
        val user = RepairNames.sourceKey(false, "fonts/SUIT-Regular.otf")
        assertNotEquals(asset, user)
        val base = RepairNames.base(asset, "7:100", 1)
        assertEquals(base, RepairNames.base(asset, "7:100", 1))
        assertNotEquals(base, RepairNames.base(asset, "8:100", 1)) // an app update
        assertNotEquals(base, RepairNames.base(asset, "7:100", 2)) // new rules
        assertTrue(base.matches(Regex("[0-9a-f]{24}-[0-9a-f]{24}")))
        val older = RepairNames.base(asset, "6:90", 1)
        assertTrue(RepairNames.isOf(older + RepairNames.FONT, asset))
        assertFalse(RepairNames.isOf(older + RepairNames.FONT, asset, base))
        assertTrue(RepairNames.isOf(base + RepairNames.OK, asset, base))
        assertTrue(RepairNames.isOf(base + RepairNames.TEMP, asset, base))
        assertFalse(RepairNames.isOf(RepairNames.base(user, "7:100", 1) + RepairNames.FONT, asset))
    }
}
