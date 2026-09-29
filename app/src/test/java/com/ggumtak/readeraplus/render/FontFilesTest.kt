package com.ggumtak.readeraplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontFilesTest {

    private val ttfHeader = byteArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
    private val otfHeader = "OTTO".toByteArray() + ByteArray(8)
    private val ttcHeader = "ttcf".toByteArray() + ByteArray(8)

    @Test
    fun fontFileNames() {
        assertTrue(FontFiles.isFontFileName("a.ttf"))
        assertTrue(FontFiles.isFontFileName("B.OTF"))
        assertTrue(FontFiles.isFontFileName("c.TtC"))
        assertFalse(FontFiles.isFontFileName("d.woff2"))
        assertFalse(FontFiles.isFontFileName("readme.txt"))
        assertFalse(FontFiles.isFontFileName("noext"))
        assertFalse(FontFiles.isFontFileName(".import-123.ttf"))
        assertEquals("user:x.ttf", FontFiles.userId("x.ttf"))
    }

    @Test
    fun extensionFromSignature() {
        assertEquals("ttf", FontFiles.extensionFor(ttfHeader))
        assertEquals("otf", FontFiles.extensionFor(otfHeader))
        assertEquals("ttc", FontFiles.extensionFor(ttcHeader))
        assertEquals("ttf", FontFiles.extensionFor(ByteArray(2)))
    }

    @Test
    fun sanitizeKeepsGoodNames() {
        assertEquals("MyFont.ttf", FontFiles.sanitizeFileName("MyFont.ttf", ttfHeader, 1))
        assertEquals("나눔글꼴.otf", FontFiles.sanitizeFileName("나눔글꼴.otf", otfHeader, 1))
        assertEquals("Font.otf", FontFiles.sanitizeFileName("Font.OTF", otfHeader, 1))
    }

    @Test
    fun sanitizeStripsPathsAndIllegalChars() {
        assertEquals("evil.ttf", FontFiles.sanitizeFileName("../../evil.ttf", ttfHeader, 1))
        assertEquals("a_b_c.ttf", FontFiles.sanitizeFileName("a:b*c.ttf", ttfHeader, 1))
        assertEquals("x.ttf", FontFiles.sanitizeFileName("dir\\x.ttf", ttfHeader, 1))
        assertEquals("hidden.ttf", FontFiles.sanitizeFileName(".hidden.ttf", ttfHeader, 1))
    }

    @Test
    fun sanitizeAddsExtensionFromHeader() {
        assertEquals("font download.otf", FontFiles.sanitizeFileName("font download", otfHeader, 1))
        assertEquals("primary_1234.ttf", FontFiles.sanitizeFileName("primary:1234", ttfHeader, 1))
        assertEquals("Collection.bin.ttc", FontFiles.sanitizeFileName("Collection.bin", ttcHeader, 1))
    }

    @Test
    fun sanitizeFallbackAndLength() {
        assertEquals("font_77.ttf", FontFiles.sanitizeFileName(null, ttfHeader, 77))
        assertEquals("font_77.ttf", FontFiles.sanitizeFileName("   ", ttfHeader, 77))
        assertEquals("font_77.otf", FontFiles.sanitizeFileName(".otf", otfHeader, 77))
        val long = "x".repeat(500) + ".ttf"
        val s = FontFiles.sanitizeFileName(long, ttfHeader, 1)
        assertTrue(s.length <= 124)
        assertTrue(s.endsWith(".ttf"))
    }

    @Test
    fun boldSiblingByRegularSuffix() {
        val names = listOf("Sample-Regular.ttf", "Sample-Bold.ttf", "Other.ttf")
        assertEquals("Sample-Bold.ttf", FontFiles.findBoldSibling("Sample-Regular.ttf", names))
        assertNull(FontFiles.findBoldSibling("Sample-Bold.ttf", names))
        assertNull(FontFiles.findBoldSibling("Other.ttf", names))
    }

    @Test
    fun boldSiblingWithoutRegularToken() {
        val names = listOf("NanumSample.ttf", "NanumSampleBold.ttf")
        assertEquals("NanumSampleBold.ttf", FontFiles.findBoldSibling("NanumSample.ttf", names))
    }

    @Test
    fun boldSiblingCaseInsensitiveAndOtherExtension() {
        val names = listOf("Foo_Regular.otf", "foo_bold.OTF")
        assertEquals("foo_bold.OTF", FontFiles.findBoldSibling("Foo_Regular.otf", names))
        val mixed = listOf("Bar-Book.ttf", "Bar-Bold.otf")
        assertEquals("Bar-Bold.otf", FontFiles.findBoldSibling("Bar-Book.ttf", mixed))
    }

    @Test
    fun boldSiblingLetterConvention() {
        assertEquals("SchoolBatangB.otf", FontFiles.findBoldSibling("SchoolBatangR.otf", listOf("SchoolBatangR.otf", "SchoolBatangB.otf")))
        assertEquals("Foo_B.ttf", FontFiles.findBoldSibling("Foo_R.ttf", listOf("Foo_R.ttf", "Foo_B.ttf")))
        // "B" suffix is not guessed for files that don't use the R/B convention
        assertNull(FontFiles.findBoldSibling("Club.ttf", listOf("Club.ttf", "ClubB.ttf")))
    }

    @Test
    fun nonRegularFacesAreNotPaired() {
        val names = listOf("Font-Light.ttf", "Font-LightBold.ttf", "Font-SemiBold.ttf", "Font-Bold.ttf", "Font-Italic.ttf")
        assertNull(FontFiles.findBoldSibling("Font-Light.ttf", names))
        assertNull(FontFiles.findBoldSibling("Font-SemiBold.ttf", names))
        assertNull(FontFiles.findBoldSibling("Font-Italic.ttf", names))
        assertNull(FontFiles.findBoldSibling("noext", names))
    }

    @Test
    fun regularStyleNames() {
        assertTrue(FontFiles.isRegularStyleName("Regular"))
        assertTrue(FontFiles.isRegularStyleName(" normal "))
        assertTrue(FontFiles.isRegularStyleName("보통"))
        assertFalse(FontFiles.isRegularStyleName("Bold"))
        assertFalse(FontFiles.isRegularStyleName("Light"))
    }

    @Test
    fun serifGuess() {
        assertFalse(FontFiles.serifGuess("나눔고딕", null))
        assertFalse(FontFiles.serifGuess("Noto Sans KR", null))
        assertTrue(FontFiles.serifGuess("나눔명조", true))
        assertTrue(FontFiles.serifGuess("Some Batang", null))
        assertFalse(FontFiles.serifGuess("Mystery", true))
        assertTrue(FontFiles.serifGuess("Mystery", false))
        assertTrue(FontFiles.serifGuess("Mystery", null))
    }

    /** Regression: a long Korean display name produced a > 255-byte file name and the import failed. */
    @Test
    fun sanitizeCapsUtf8BytesForKoreanNames() {
        val name = "아주 긴 한글 글꼴 이름".repeat(12) + ".ttf"
        val s = FontFiles.sanitizeFileName(name, ttfHeader, 1)
        assertTrue(s.endsWith(".ttf"))
        assertTrue("bytes=${s.toByteArray(Charsets.UTF_8).size}", s.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(s.startsWith("아주 긴 한글"))
    }

    @Test
    fun truncateNeverSplitsSurrogatePairs() {
        val emoji = "😀"
        val t = FontFiles.truncate("a$emoji$emoji", 2, 100)
        assertEquals("a", t) // the pair would straddle the 2-char limit
        assertEquals("a$emoji", FontFiles.truncate("a$emoji$emoji", 3, 100))
        assertEquals("a", FontFiles.truncate("a$emoji", 10, 4)) // 1 + 4 bytes > 4
        assertEquals("a$emoji", FontFiles.truncate("a$emoji", 10, 5))
        val cut = FontFiles.sanitizeFileName("x".repeat(119) + emoji + "y.otf", otfHeader, 1)
        assertEquals("x".repeat(119) + ".otf", cut)
    }

    @Test
    fun truncateByteCounts() {
        assertEquals("abc", FontFiles.truncate("abc", 10, 10))
        assertEquals("가나", FontFiles.truncate("가나다", 10, 8)) // 3 bytes each
        assertEquals("éé", FontFiles.truncate("ééé", 10, 5)) // 2 bytes each
        assertEquals("", FontFiles.truncate("가", 10, 2))
    }

    @Test
    fun prebuiltIndexMatchesListLookup() {
        val names = listOf("Sample-Regular.ttf", "Sample-Bold.ttf", "NanumSample.ttf", "NanumSampleBold.otf", "Foo_R.ttf", "Foo_B.ttf", "x.ttf")
        val index = FontFiles.lowerIndex(names)
        for (n in names) assertEquals(n, FontFiles.findBoldSibling(n, names), FontFiles.findBoldSibling(n, index))
        assertEquals("NanumSampleBold.otf", FontFiles.findBoldSibling("NanumSample.ttf", index))
    }
}
